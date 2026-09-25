from __future__ import annotations

from ledgerflow_agent.models import (
    ModelReply,
    ModelUnavailable,
    ScriptedModelClient,
    ToolCall,
)
from ledgerflow_agent.schemas import ProposalSource, TransactionOutcome
from ledgerflow_agent.transaction_investigation import TransactionInvestigator

from .conftest import (
    SIBLING_ID,
    TRANSACTION_ID,
    FakeCore,
    coa_usage,
    counterparty_history,
    transaction,
)


def finding(outcome: str, **arguments) -> ModelReply:
    arguments.setdefault("confidence", 0.9)
    arguments.setdefault("rationale", "because")
    return ModelReply(text="", tool_calls=(ToolCall(
        id="call_final", name="record_finding",
        arguments={"outcome": outcome, **arguments}),))


def look_up(name: str, **arguments) -> ModelReply:
    return ModelReply(text="", tool_calls=(ToolCall(id=f"call_{name}", name=name,
                                                    arguments=arguments),))


def insists(outcome: str, **arguments) -> list[ModelReply]:
    """A model that repeats itself after correction, so the rejection stands."""
    return [finding(outcome, **arguments), finding(outcome, **arguments)]


async def investigate(core: FakeCore, model: ScriptedModelClient):
    client = core.client()
    try:
        return await TransactionInvestigator(client, model).investigate(TRANSACTION_ID)
    finally:
        await client.aclose()


async def test_a_counterparty_with_a_settled_history_never_reaches_the_model():
    core = FakeCore()
    core.counterparty_history = counterparty_history(
        "Northwind Cloud", coa_usage("6000", "Software Subscriptions", 9))
    model = ScriptedModelClient([])

    result = await investigate(core, model)

    assert result.source is ProposalSource.DETERMINISTIC
    assert result.outcome is TransactionOutcome.CATEGORIZE
    assert result.chart_of_account_code == "6000"
    assert result.model_calls == 0
    assert model.calls == []


async def test_a_single_prior_sighting_is_not_treated_as_a_rule():
    core = FakeCore()
    core.counterparty_history = counterparty_history(
        "Northwind Cloud", coa_usage("6000", "Software Subscriptions", 1))
    model = ScriptedModelClient([finding("ESCALATE", confidence=0.0, rationale="too thin")])

    result = await investigate(core, model)

    assert result.source is ProposalSource.AGENT


async def test_a_counterparty_categorized_several_ways_reaches_the_model():
    core = FakeCore()
    core.counterparty_history = counterparty_history(
        "Harbor Point", coa_usage("6700", "Office Supplies", 4),
        coa_usage("6500", "Meals and Entertainment", 3))
    model = ScriptedModelClient([
        finding("REQUEST_CONTEXT", confidence=0.0, rationale="genuinely mixed",
                question="Was the Harbor Point charge supplies or a client lunch?"),
    ])

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.REQUEST_CONTEXT
    assert result.question is not None
    assert result.source is ProposalSource.AGENT


async def test_the_brief_withholds_everything_but_the_transaction_itself():
    core = FakeCore()
    model = ScriptedModelClient([finding("ESCALATE", confidence=0.0, rationale="no evidence")])

    await investigate(core, model)

    brief = model.calls[0].turns[0]["text"]
    assert "TRANSFER TO SAVINGS *9082" in brief
    assert "Business Checking" in brief
    # No categories are named, or the investigation collapses back into classification.
    assert "6000" not in brief and "Software Subscriptions" not in brief


async def test_an_internal_transfer_is_confirmed_against_the_balancing_entry():
    core = FakeCore()
    core.offsetting = [transaction(SIBLING_ID, amount="5000.00",
                                   description="TRANSFER FROM CHECKING *4417",
                                   account_name="Business Savings")]
    model = ScriptedModelClient([
        look_up("get_counterparty_history"),
        look_up("find_offsetting_transactions", window_days=3),
        finding("INTERNAL_TRANSFER", chart_of_account_code="1010",
                related_transaction_id=SIBLING_ID,
                evidence=["offsetting +5000.00 in Business Savings on the same day"]),
    ])

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.INTERNAL_TRANSFER
    assert result.related_transaction_id == SIBLING_ID
    assert result.rejected_reasons == []
    assert result.capability_sequence == ["get_counterparty_history",
                                          "find_offsetting_transactions"]


async def test_an_unsupported_transfer_claim_is_rejected_rather_than_recorded():
    core = FakeCore()
    core.offsetting = []
    model = ScriptedModelClient(insists("INTERNAL_TRANSFER", chart_of_account_code="1010",
                                       related_transaction_id=SIBLING_ID))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.rejected_reasons == [
        "the named transaction does not offset this one in a sibling account"]


async def test_a_transfer_that_does_not_balance_is_rejected_even_if_the_core_offered_it():
    core = FakeCore()
    # The core's query filters to exactly offsetting amounts, so this cannot arise today. The
    # validator re-derives the arithmetic anyway: widening that query must not widen what counts
    # as a transfer.
    core.offsetting = [transaction(SIBLING_ID, amount="4300.00",
                                   account_name="Business Savings")]
    model = ScriptedModelClient(insists("INTERNAL_TRANSFER", related_transaction_id=SIBLING_ID))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert "does not balance this one" in result.rejected_reasons[0]


async def test_a_duplicate_of_a_different_amount_is_rejected():
    core = FakeCore()
    core.similar = [transaction(SIBLING_ID, amount="-4100.00")]
    model = ScriptedModelClient(insists("DUPLICATE", related_transaction_id=SIBLING_ID))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert "must be the same charge" in result.rejected_reasons[0]


async def test_a_transfer_booked_to_revenue_is_rejected():
    core = FakeCore()
    core.offsetting = [transaction(SIBLING_ID, amount="5000.00")]
    model = ScriptedModelClient(insists("INTERNAL_TRANSFER", chart_of_account_code="4000",
                                       related_transaction_id=SIBLING_ID))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert "must book to an ASSET account" in result.rejected_reasons[0]


async def test_money_paid_out_cannot_be_recorded_as_revenue():
    core = FakeCore()
    core.counterparty_history = counterparty_history("Harbor Point")
    model = ScriptedModelClient(insists("CATEGORIZE", chart_of_account_code="4000"))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.rejected_reasons == ["money paid out cannot book to a REVENUE account"]


async def test_money_received_cannot_be_recorded_as_an_expense():
    core = FakeCore(txn=transaction(amount="1200.00", description="DEPOSIT",
                                    counterparty="Harbor Point"))
    model = ScriptedModelClient(insists("CATEGORIZE", chart_of_account_code="6700"))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.rejected_reasons == ["money received cannot book to an EXPENSE account"]


async def test_a_refund_may_reverse_an_expense_account_despite_being_an_inflow():
    core = FakeCore(txn=transaction(amount="240.00", description="REFUND NORTHWIND CLOUD",
                                    counterparty="Northwind Cloud"))
    model = ScriptedModelClient([finding("MATCH_REFUND", chart_of_account_code="6000")])

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.MATCH_REFUND
    assert result.rejected_reasons == []


async def test_a_rejected_finding_may_be_corrected_once_and_is_counted_as_such():
    core = FakeCore()
    core.counterparty_history = counterparty_history("Harbor Point")
    model = ScriptedModelClient([
        finding("CATEGORIZE", chart_of_account_code="Expenses:Office"),
        look_up("list_chart_of_accounts"),
        finding("CATEGORIZE", chart_of_account_code="6700"),
    ])

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.CATEGORIZE
    assert result.chart_of_account_code == "6700"
    assert result.correction_attempts == 1


async def test_only_one_correction_is_offered():
    core = FakeCore()
    core.counterparty_history = counterparty_history("Harbor Point")
    model = ScriptedModelClient([
        finding("CATEGORIZE", chart_of_account_code="Expenses:Office"),
        finding("CATEGORIZE", chart_of_account_code="Expenses:Supplies"),
        finding("CATEGORIZE", chart_of_account_code="6700"),
    ])

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.correction_attempts == 1
    assert result.rejected_reasons == ["unknown chart of account code: Expenses:Supplies"]


async def test_an_unknown_account_code_is_rejected():
    core = FakeCore()
    core.counterparty_history = counterparty_history("Harbor Point")
    model = ScriptedModelClient(insists("CATEGORIZE", chart_of_account_code="9999"))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.rejected_reasons == ["unknown chart of account code: 9999"]


async def test_a_duplicate_claim_is_checked_against_the_similar_set():
    core = FakeCore()
    core.similar = [transaction(SIBLING_ID, amount="-5000.00")]
    model = ScriptedModelClient([
        look_up("find_similar_transactions", window_days=7),
        finding("DUPLICATE", related_transaction_id=SIBLING_ID),
    ])

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.DUPLICATE
    assert result.rejected_reasons == []


async def test_a_duplicate_claim_without_a_matching_transaction_is_rejected():
    core = FakeCore()
    core.similar = []
    model = ScriptedModelClient(insists("DUPLICATE", related_transaction_id=SIBLING_ID))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.rejected_reasons == [
        "the named transaction does not share this counterparty and amount"]


async def test_a_transaction_cannot_be_declared_a_duplicate_of_itself():
    core = FakeCore()
    core.similar = [transaction(TRANSACTION_ID)]
    model = ScriptedModelClient(insists("DUPLICATE", related_transaction_id=TRANSACTION_ID))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.rejected_reasons == ["a transaction cannot be related to itself"]


async def test_an_outcome_missing_its_required_relation_is_rejected():
    core = FakeCore()
    model = ScriptedModelClient(insists("INTERNAL_TRANSFER", chart_of_account_code="1010"))

    result = await investigate(core, model)

    assert result.outcome is TransactionOutcome.ESCALATE
    assert "requires a related_transaction_id" in result.rejected_reasons[0]


class UnreachableModelClient:
    """A model that cannot be reached at all — a timeout, a refusal, or a dead endpoint."""

    name = "unreachable"

    def __init__(self) -> None:
        self.calls = 0

    async def complete(self, conversation, tools):
        self.calls += 1
        raise ModelUnavailable("ollama request failed: timed out")


async def test_an_unreachable_model_escalates_to_a_human_rather_than_failing():
    core = FakeCore()
    model = UnreachableModelClient()
    client = core.client()
    try:
        result = await TransactionInvestigator(client, model).investigate(TRANSACTION_ID)
    finally:
        await client.aclose()

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.source is ProposalSource.AGENT
    assert "could not be reached" in result.rationale
    # One attempt, not one per remaining iteration: an unreachable model will not become reachable
    # by asking again inside the same investigation.
    assert model.calls == 1


async def test_a_transaction_settled_by_precedent_survives_an_unreachable_model():
    core = FakeCore()
    core.counterparty_history = counterparty_history(
        "Northwind Cloud", coa_usage("6000", "Software Subscriptions", 9))
    model = UnreachableModelClient()
    client = core.client()
    try:
        result = await TransactionInvestigator(client, model).investigate(TRANSACTION_ID)
    finally:
        await client.aclose()

    assert result.outcome is TransactionOutcome.CATEGORIZE
    assert result.source is ProposalSource.DETERMINISTIC
    assert model.calls == 0


async def test_the_last_investigation_step_is_announced_so_the_model_can_reassess():
    core = FakeCore()
    model = ScriptedModelClient([look_up("get_counterparty_history") for _ in range(3)])
    client = core.client()
    try:
        await TransactionInvestigator(client, model, max_iterations=3).investigate(TRANSACTION_ID)
    finally:
        await client.aclose()

    # The prompt tells the model the final step is a moment to reassess, which is only actionable
    # if it is told when it has arrived there.
    final_turn_texts = [turn.get("text") for turn in model.calls[-1].turns
                        if turn["role"] == "user"]
    assert any(text and "your last step" in text for text in final_turn_texts)


async def test_running_out_of_steps_escalates_rather_than_guessing():
    core = FakeCore()
    model = ScriptedModelClient([look_up("get_counterparty_history") for _ in range(3)])
    client = core.client()
    try:
        result = await TransactionInvestigator(client, model,
                                               max_iterations=3).investigate(TRANSACTION_ID)
    finally:
        await client.aclose()

    assert result.outcome is TransactionOutcome.ESCALATE
    assert result.model_calls == 3
    assert result.capability_calls == 3


async def test_capabilities_cannot_be_pointed_at_another_transaction():
    core = FakeCore()
    model = ScriptedModelClient([finding("ESCALATE", confidence=0.0, rationale="none")])

    await investigate(core, model)

    specs = {spec.name for spec in _tool_specs(core)}
    assert specs == {"get_counterparty_history", "find_offsetting_transactions",
                     "find_similar_transactions", "search_transactions", "list_chart_of_accounts"}
    for spec in _tool_specs(core):
        assert "transaction_id" not in spec.input_schema.get("properties", {})


def _tool_specs(core: FakeCore):
    from ledgerflow_agent.capabilities import TransactionCapabilityRegistry

    return TransactionCapabilityRegistry(core.client(), "business", TRANSACTION_ID).tool_specs()
