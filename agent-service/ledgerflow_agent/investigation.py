from __future__ import annotations

import json
from typing import Any

from .capabilities import CapabilityError, CapabilityRegistry
from .core_client import FinancialCoreClient
from .money import parse_money
from .models import Conversation, ModelClient, ModelUnavailable, ToolResult, ToolSpec
from .proposal_validator import ProposalRejected, validate_allocations
from .schemas import (
    CandidateSet,
    CandidateView,
    Decision,
    ProposalSource,
    ReconciliationProposal,
)

SYSTEM_PROMPT = """\
You are a bookkeeping analyst for a small business. A customer payment could not be matched to an \
invoice with confidence by the deterministic matcher, and you are deciding what to propose.

You cannot change any financial record. You produce a proposal that a human approves or rejects.

The deterministic matcher has already filtered and scored the plausible invoices; its signals are:
- EXACT_OUTSTANDING_MATCH / EXACT_TOTAL_MATCH: the amount lines up exactly.
- AMOUNT_WITHIN_TOLERANCE: within 2%, which usually means a fee or short-pay.
- PARTIALLY_COVERS_PAYMENT: the invoice is smaller than the payment, so the payment may settle
  several invoices together.
- PARTIAL_PAYMENT_OF_INVOICE: the payment is smaller than the invoice, so it may be an instalment.
- REFERENCE_CONTAINS_INVOICE_NUMBER: the strongest single piece of evidence.
- SAME_CUSTOMER / PAYER_NAME_SIMILARITY: counterparty agreement.
- DUE_DATE_PROXIMITY: weak corroboration only.

Use the read capabilities to resolve the ambiguity. Then call exactly one of:
- propose_allocation, when the evidence identifies which invoices the payment settles. You may
  split one payment across several invoices. Never allocate more than an invoice's outstanding
  amount, and never allocate more than the payment's unapplied amount in total.
- escalate, when the evidence is genuinely insufficient. Escalating is the correct answer for a
  true coin-flip; a confident wrong allocation is far more costly than an escalation.

Amounts must be decimal strings, never rounded or approximated."""

TERMINAL_TOOLS = [
    ToolSpec(
        name="propose_allocation",
        description="Propose how this payment settles one or more invoices.",
        input_schema={
            "type": "object",
            "properties": {
                "allocations": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "invoice_id": {"type": "string"},
                            "amount": {"type": "string",
                                       "description": "Decimal string, e.g. \"1250.00\"."},
                        },
                        "required": ["invoice_id", "amount"],
                    },
                },
                "confidence": {"type": "number", "minimum": 0, "maximum": 1},
                "rationale": {"type": "string",
                              "description": "The evidence, in one or two sentences."},
            },
            "required": ["allocations", "confidence", "rationale"],
        },
    ),
    ToolSpec(
        name="escalate",
        description="Escalate to a human because the evidence cannot identify the right invoice.",
        input_schema={
            "type": "object",
            "properties": {
                "rationale": {"type": "string",
                              "description": "What is ambiguous and what would resolve it."},
            },
            "required": ["rationale"],
        },
    ),
]


def parse_candidate_set(raw: dict) -> CandidateSet:
    return CandidateSet(
        payment_id=raw["paymentId"],
        unapplied_amount=parse_money(raw["unappliedAmount"]),
        considered_invoice_count=raw["consideredInvoiceCount"],
        ambiguous=raw["ambiguous"],
        candidates=[
            CandidateView(
                invoice_id=candidate["invoiceId"],
                invoice_number=candidate["invoiceNumber"],
                customer_name=candidate["customerName"],
                issue_date=candidate["issueDate"],
                due_date=candidate["dueDate"],
                total_amount=parse_money(candidate["totalAmount"]),
                outstanding_amount=parse_money(candidate["outstandingAmount"]),
                suggested_amount=parse_money(candidate["suggestedAmount"]),
                score=candidate["score"],
                signals=candidate["signals"],
            )
            for candidate in raw["candidates"]
        ],
    )


class ReconciliationInvestigator:
    def __init__(self, client: FinancialCoreClient, model: ModelClient,
                 max_iterations: int = 6) -> None:
        self._client = client
        self._model = model
        self._max_iterations = max_iterations

    async def investigate(self, payment_id: str) -> ReconciliationProposal:
        candidate_set = parse_candidate_set(
            await self._client.get_reconciliation_candidates(payment_id))

        if not candidate_set.ambiguous:
            return self._deterministic_proposal(candidate_set)

        payment = await self._client.get_payment(payment_id)
        registry = CapabilityRegistry(self._client, payment["businessId"])
        return await self._run_agent(candidate_set, payment, registry)

    def _deterministic_proposal(self, candidate_set: CandidateSet) -> ReconciliationProposal:
        """The matcher was already confident, so no model is invoked at all. The share of payments
        that land here is the candidate-reduction metric reported in P3."""
        top = candidate_set.candidates[0]
        return ReconciliationProposal(
            payment_id=candidate_set.payment_id,
            decision=Decision.APPLY,
            allocations=[{"invoice_id": top.invoice_id, "invoice_number": top.invoice_number,
                          "amount": top.suggested_amount.amount}],
            confidence=top.score,
            rationale=f"Deterministic match on {', '.join(top.signals)}.",
            source=ProposalSource.DETERMINISTIC,
            considered_invoice_count=candidate_set.considered_invoice_count,
            candidate_count=len(candidate_set.candidates),
        )

    async def _run_agent(self, candidate_set: CandidateSet, payment: dict,
                         registry: CapabilityRegistry) -> ReconciliationProposal:
        conversation = Conversation(system=SYSTEM_PROMPT)
        conversation.add_user(_brief(candidate_set, payment))
        tools = registry.tool_specs() + TERMINAL_TOOLS

        model_calls = 0
        capability_calls = 0

        for _ in range(self._max_iterations):
            try:
                reply = await self._model.complete(conversation, tools)
            except ModelUnavailable as exc:
                # Tier 3. A payment the model could not be asked about is a payment for a human.
                return self._escalation(candidate_set, f"The model could not be reached: {exc}",
                                        model_calls, capability_calls)
            model_calls += 1
            conversation.add_assistant(reply)

            if not reply.tool_calls:
                conversation.add_user(
                    "Call propose_allocation or escalate to finish.")
                continue

            terminal = next((call for call in reply.tool_calls
                             if call.name in {"propose_allocation", "escalate"}), None)
            if terminal is not None:
                return self._finish(terminal.name, terminal.arguments, candidate_set,
                                    model_calls, capability_calls)

            results: list[ToolResult] = []
            for call in reply.tool_calls:
                capability_calls += 1
                try:
                    content = await registry.invoke(call.name, call.arguments)
                except CapabilityError as exc:
                    content = json.dumps({"error": str(exc)})
                results.append(ToolResult(call_id=call.id, content=content))
            conversation.add_tool_results(results)

        return self._escalation(candidate_set, "Reached the investigation step limit without a "
                                               "conclusion.", model_calls, capability_calls)

    def _finish(self, tool_name: str, arguments: dict[str, Any], candidate_set: CandidateSet,
                model_calls: int, capability_calls: int) -> ReconciliationProposal:
        if tool_name == "escalate":
            return self._escalation(candidate_set,
                                    arguments.get("rationale", "No rationale supplied."),
                                    model_calls, capability_calls)

        rationale = arguments.get("rationale", "")
        try:
            allocations = validate_allocations(arguments.get("allocations"), candidate_set)
        except ProposalRejected as rejected:
            proposal = self._escalation(
                candidate_set,
                f"The proposed allocation failed deterministic validation. {rationale}",
                model_calls, capability_calls)
            proposal.rejected_reasons = rejected.reasons
            return proposal

        confidence = arguments.get("confidence", 0.0)
        return ReconciliationProposal(
            payment_id=candidate_set.payment_id,
            decision=Decision.APPLY,
            allocations=allocations,
            confidence=float(confidence),
            rationale=rationale,
            source=ProposalSource.AGENT,
            model_name=self._model.name,
            considered_invoice_count=candidate_set.considered_invoice_count,
            candidate_count=len(candidate_set.candidates),
            model_calls=model_calls,
            capability_calls=capability_calls,
        )

    def _escalation(self, candidate_set: CandidateSet, rationale: str, model_calls: int,
                    capability_calls: int) -> ReconciliationProposal:
        return ReconciliationProposal(
            payment_id=candidate_set.payment_id,
            decision=Decision.ESCALATE,
            confidence=0.0,
            rationale=rationale,
            source=ProposalSource.AGENT,
            model_name=self._model.name,
            considered_invoice_count=candidate_set.considered_invoice_count,
            candidate_count=len(candidate_set.candidates),
            model_calls=model_calls,
            capability_calls=capability_calls,
        )


def _brief(candidate_set: CandidateSet, payment: dict) -> str:
    lines = [
        "Payment awaiting reconciliation:",
        f"  received: {payment['receivedDate']}",
        f"  amount unapplied: {candidate_set.unapplied_amount}",
        f"  method: {payment['method']}",
        f"  bank reference: {payment.get('reference') or '(none)'}",
        f"  payer as written: {payment.get('payerName') or '(unknown)'}",
        f"  resolved customer: {payment.get('customerName') or '(unresolved)'}",
        f"  customer id: {payment.get('customerId') or '(none)'}",
        "",
        f"The matcher considered {candidate_set.considered_invoice_count} open invoices and could "
        f"not settle on one. Ranked candidates:",
    ]
    for index, candidate in enumerate(candidate_set.candidates, start=1):
        lines.append(
            f"  {index}. {candidate.invoice_number} — {candidate.customer_name}\n"
            f"     invoice_id: {candidate.invoice_id}\n"
            f"     due {candidate.due_date}, total {candidate.total_amount}, "
            f"outstanding {candidate.outstanding_amount}\n"
            f"     score {candidate.score:.2f}, signals: {', '.join(candidate.signals) or 'none'}"
        )
    if not candidate_set.candidates:
        lines.append("  (none — no invoice in the search window is plausible)")
    return "\n".join(lines)
