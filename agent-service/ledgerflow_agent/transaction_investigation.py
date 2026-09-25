from __future__ import annotations

import json
from typing import Any

from .capabilities import CapabilityError, TransactionCapabilityRegistry
from .core_client import FinancialCoreClient
from .models import Conversation, ModelClient, ModelUnavailable, ToolResult, ToolSpec
from .schemas import (
    ProposalSource,
    TransactionFinding,
    TransactionOutcome,
)
from .transaction_validator import (
    FindingRejected,
    ValidationContext,
    derive_transfer_account,
    validate_finding,
)

#: How many prior sightings a counterparty needs before its single consistent code is trusted
#: without investigation. One precedent is an anecdote; a run of them is a rule.
MIN_CONSISTENT_HISTORY = 3

#: A rejected finding is handed back to the model once, with the reason, so it can retrieve what it
#: missed. This is not a silent repair: nothing is patched on the model's behalf, and the retry is
#: re-validated against evidence re-read from the core. Findings that survive a correction are
#: counted separately so the retry cannot quietly flatter the accuracy figures.
MAX_CORRECTIONS = 1

SYSTEM_PROMPT = """\
You are a bookkeeping analyst for a small business. One bank transaction is uncategorized and you \
must work out what it represents.

The question is not "which category best fits this description?" It is: what does this transaction \
actually represent, given the surrounding financial record? Decide that first; the account code, \
if any, follows from it.

You cannot change any record. You produce a finding that a human approves or rejects.

You are given only the transaction itself. Everything else must be earned by calling capabilities, \
and each result should determine what you look at next. A counterparty you have never categorized \
before is a reason to look for a balancing entry in another account, not a reason to guess.

Finish by calling record_finding exactly once with one of these outcomes:
- CATEGORIZE: an ordinary business transaction. Supply chart_of_account_code.
- INTERNAL_TRANSFER: money moved between the business's own accounts. Supply the \
related_transaction_id of the balancing entry; the account code is derived from it, so you need \
not supply one. This is neither revenue nor an expense.
- MATCH_REFUND: money back from a vendor, reversing an earlier expense. Supply the expense \
chart_of_account_code being reversed.
- MATCH_PAYMENT: money in that settles a customer invoice.
- RECURRING_EXPENSE: a charge that repeats on a schedule. Supply chart_of_account_code.
- DUPLICATE: the same charge booked twice. Supply the related_transaction_id it duplicates.
- REQUEST_CONTEXT: the record cannot settle this, but a specific question to the owner would. \
Supply that question.
- ESCALATE: the evidence is genuinely insufficient and no single question would resolve it.

REQUEST_CONTEXT and ESCALATE are correct answers, not failures. A counterparty with a genuinely \
mixed history and nothing to break the tie should not be guessed at. A confident wrong answer costs \
far more than an abstention.

Evidence sufficiency is outcome-specific.

Direct corroborating evidence can be sufficient on its own. Examples include a validated offsetting \
transaction for an internal transfer, a verified matching transaction for a duplicate or a refund, \
and a historical precedent that uniquely supports one category.

Evidence that is not required for the outcome you are proposing must not reduce your confidence. \
If you have confirmed a balancing entry, it does not matter that the counterparty has no history: \
history was never what made that conclusion true.

Abstain or request context when both of these hold:
- more than one plausible explanation remains, and
- the evidence you retrieved does not distinguish among them.

Do not choose the nearest plausible category merely because it is a valid one.

Stop investigating once you hold direct corroborating evidence for one explanation. Before every \
capability call, ask what result would change your answer. If no result could change it, you are no \
longer investigating, and you should record your finding instead. Your budget of steps is finite, \
and spending it on calls that cannot change the outcome is how an already-settled question ends up \
unanswered.

Reaching the last investigation step you are allowed is a reason to reassess rather than to commit. \
If you have found nothing decisive by then, prefer REQUEST_CONTEXT when one specific missing fact \
would settle it, and ESCALATE otherwise.

Cite the evidence you actually retrieved. Do not cite evidence you did not look at."""

#: Delivered when the investigation is one step from its limit. The prompt tells the model that the
#: last step is a moment to reassess, which is only actionable if it knows it has arrived there.
FINAL_STEP_NOTICE = (
    "This is your last step, so record your finding now. Review what you have already retrieved "
    "before deciding: if it corroborates one explanation, record that outcome. Abstain only if "
    "several explanations genuinely remain and nothing you retrieved distinguishes among them — "
    "REQUEST_CONTEXT when one specific missing fact would settle it, otherwise ESCALATE."
)

RECORD_FINDING = ToolSpec(
    name="record_finding",
    description="Record what this transaction represents. Call exactly once, at the end.",
    input_schema={
        "type": "object",
        "properties": {
            "outcome": {
                "type": "string",
                "enum": [outcome.value for outcome in TransactionOutcome],
            },
            "chart_of_account_code": {
                "type": "string",
                "pattern": "^[0-9]{4}$",
                "description": (
                    "The bare four-digit `code` field of a chart-of-accounts entry, for example "
                    "\"6700\". Never the name, the category, or a path built from them. Required "
                    "for CATEGORIZE, RECURRING_EXPENSE, MATCH_REFUND and INTERNAL_TRANSFER."
                ),
            },
            "related_transaction_id": {
                "type": "string",
                "description": (
                    "The id of the other transaction involved. REQUIRED for INTERNAL_TRANSFER "
                    "(the balancing entry) and for DUPLICATE (the transaction duplicated). Copy "
                    "the exact id field from the capability result; a finding without it is "
                    "rejected."
                ),
            },
            "confidence": {"type": "number", "minimum": 0, "maximum": 1},
            "rationale": {"type": "string", "description": "The evidence, in one or two sentences."},
            "question": {
                "type": "string",
                "description": "For REQUEST_CONTEXT: the one question that would resolve this.",
            },
            "evidence": {
                "type": "array",
                "items": {"type": "string"},
                "description": "The specific facts you retrieved that support the outcome.",
            },
        },
        "required": ["outcome", "confidence", "rationale"],
    },
)


class TransactionInvestigator:
    def __init__(self, client: FinancialCoreClient, model: ModelClient,
                 max_iterations: int = 8) -> None:
        self._client = client
        self._model = model
        self._max_iterations = max_iterations

    async def investigate(self, transaction_id: str) -> TransactionFinding:
        transaction = await self._client.get_transaction(transaction_id)
        history = await self._client.get_counterparty_history(transaction_id, example_limit=1)

        settled = self._settled_by_precedent(transaction, history)
        if settled is not None:
            return settled

        return await self._run_agent(transaction)

    def _settled_by_precedent(self, transaction: dict[str, Any],
                              history: dict[str, Any]) -> TransactionFinding | None:
        """Tier 1. A counterparty categorized the same way often enough is a rule, not a judgement
        call, so no model is constructed. The share of transactions settled here is the model
        avoidance rate."""
        if not history["consistentlyCategorized"]:
            return None
        if history["categorizedCount"] < MIN_CONSISTENT_HISTORY:
            return None

        code = history["codes"][0]
        return TransactionFinding(
            transaction_id=transaction["id"],
            outcome=TransactionOutcome.CATEGORIZE,
            chart_of_account_code=code["chartOfAccountCode"],
            confidence=1.0,
            rationale=(
                f"{history['counterparty']} has been categorized as {code['chartOfAccountCode']} "
                f"{code['transactionCount']} times and never otherwise."
            ),
            source=ProposalSource.DETERMINISTIC,
            evidence=[f"counterparty history: {code['transactionCount']} prior transactions, "
                      f"all {code['chartOfAccountCode']} {code['chartOfAccountName']}"],
        )

    async def _run_agent(self, transaction: dict[str, Any]) -> TransactionFinding:
        registry = TransactionCapabilityRegistry(self._client, transaction["businessId"],
                                                 transaction["id"])
        conversation = Conversation(system=SYSTEM_PROMPT)
        conversation.add_user(_brief(transaction))
        tools = registry.tool_specs() + [RECORD_FINDING]

        model_calls = 0
        corrections = 0
        input_tokens = 0
        output_tokens = 0
        sequence: list[str] = []
        last_rejection: list[str] = []

        for step in range(self._max_iterations):
            # Delivered before the final call rather than after a tool result, so an investigation
            # that spent its budget on dead ends still gets the chance to reassess.
            if step == self._max_iterations - 1:
                conversation.add_user(FINAL_STEP_NOTICE)
            try:
                reply = await self._model.complete(conversation, tools)
            except ModelUnavailable as exc:
                # Tier 3. A transaction the model could not be asked about is a transaction for a
                # human, which is the same answer as reasoning that ran out of room.
                return self._abstention(transaction, TransactionOutcome.ESCALATE,
                                        f"The model could not be reached: {exc}",
                                        model_calls, sequence, (input_tokens, output_tokens))
            model_calls += 1
            input_tokens += reply.input_tokens
            output_tokens += reply.output_tokens
            conversation.add_assistant(reply)

            if not reply.tool_calls:
                conversation.add_user("Call record_finding to finish.")
                continue

            terminal = next((call for call in reply.tool_calls
                             if call.name == RECORD_FINDING.name), None)
            if terminal is not None:
                try:
                    return await self._finish(terminal.arguments, transaction, model_calls,
                                              sequence, corrections,
                                              (input_tokens, output_tokens))
                except FindingRejected as rejected:
                    last_rejection = rejected.reasons
                    if corrections >= MAX_CORRECTIONS:
                        break
                    corrections += 1
                    conversation.add_tool_results([ToolResult(
                        call_id=terminal.id,
                        content=json.dumps({"rejected": rejected.reasons}))])
                    conversation.add_user(
                        "That finding was rejected by deterministic validation. Read the reason. "
                        "If it concerns the finding itself — a missing or malformed field — you "
                        "already hold what you need, so call record_finding again immediately "
                        "without retrieving anything further. Retrieve only if the reason shows "
                        "your evidence does not support the outcome. If you cannot support a "
                        "conclusion, use REQUEST_CONTEXT or ESCALATE.")
                    continue

            results: list[ToolResult] = []
            for call in reply.tool_calls:
                sequence.append(call.name)
                try:
                    content = await registry.invoke(call.name, call.arguments)
                except CapabilityError as exc:
                    content = json.dumps({"error": str(exc)})
                results.append(ToolResult(call_id=call.id, content=content))
            conversation.add_tool_results(results)

        rationale = ("The finding failed deterministic validation." if last_rejection
                     else "Reached the investigation step limit without a conclusion.")
        finding = self._abstention(transaction, TransactionOutcome.ESCALATE, rationale,
                                   model_calls, sequence, (input_tokens, output_tokens))
        finding.rejected_reasons = last_rejection
        finding.correction_attempts = corrections
        return finding

    async def _finish(self, arguments: dict[str, Any], transaction: dict[str, Any],
                      model_calls: int, sequence: list[str], corrections: int,
                      tokens: tuple[int, int] = (0, 0)) -> TransactionFinding:
        """Raises FindingRejected so the caller can decide between a retry and an escalation."""
        rationale = arguments.get("rationale", "")
        try:
            outcome = TransactionOutcome(arguments.get("outcome"))
        except ValueError:
            raise FindingRejected([f"unknown outcome: {arguments.get('outcome')!r}"]) from None

        code = arguments.get("chart_of_account_code")
        related_id = arguments.get("related_transaction_id")
        context = await self._validation_context(outcome, transaction)

        validate_finding(outcome, code, related_id, context)

        if outcome is TransactionOutcome.INTERNAL_TRANSFER and related_id is not None:
            code = derive_transfer_account(related_id, context) or code

        return TransactionFinding(
            transaction_id=transaction["id"],
            outcome=outcome,
            chart_of_account_code=code,
            related_transaction_id=related_id,
            confidence=float(arguments.get("confidence", 0.0)),
            rationale=rationale,
            question=arguments.get("question"),
            source=ProposalSource.AGENT,
            model_name=self._model.name,
            evidence=list(arguments.get("evidence") or []),
            model_calls=model_calls,
            capability_calls=len(sequence),
            capability_sequence=list(sequence),
            correction_attempts=corrections,
            input_tokens=tokens[0],
            output_tokens=tokens[1],
        )

    async def _validation_context(self, outcome: TransactionOutcome,
                                  transaction: dict[str, Any]) -> ValidationContext:
        """Re-read the evidence rather than trusting what the model reported about it."""
        transaction_id = transaction["id"]
        offsetting: list[dict[str, Any]] = []
        similar: list[dict[str, Any]] = []

        if outcome is TransactionOutcome.INTERNAL_TRANSFER:
            offsetting = await self._client.get_offsetting_candidates(transaction_id, 30)
        if outcome is TransactionOutcome.DUPLICATE:
            similar = await self._client.get_similar_transactions(transaction_id, 30)

        return ValidationContext(
            transaction=transaction,
            chart_of_accounts=await self._client.list_chart_of_accounts(transaction["businessId"]),
            offsetting_candidates=offsetting,
            similar_transactions=similar,
        )

    def _abstention(self, transaction: dict[str, Any], outcome: TransactionOutcome,
                    rationale: str, model_calls: int, sequence: list[str],
                    tokens: tuple[int, int] = (0, 0)) -> TransactionFinding:
        return TransactionFinding(
            transaction_id=transaction["id"],
            outcome=outcome,
            confidence=0.0,
            rationale=rationale,
            source=ProposalSource.AGENT,
            model_name=self._model.name,
            model_calls=model_calls,
            capability_calls=len(sequence),
            capability_sequence=list(sequence),
            input_tokens=tokens[0],
            output_tokens=tokens[1],
        )


def _brief(transaction: dict[str, Any]) -> str:
    """Deliberately minimal. Naming plausible categories here would collapse the investigation back
    into classification, which is the thing this design is trying to avoid."""
    return "\n".join([
        "Uncategorized bank transaction:",
        f"  id: {transaction['id']}",
        f"  booked: {transaction['bookedDate']}",
        f"  description: {transaction['description']}",
        f"  counterparty as written: {transaction['counterparty']}",
        f"  amount: {transaction['amount']['amount']} {transaction['amount']['currency']}",
        f"  account: {transaction['accountName']}",
    ])
