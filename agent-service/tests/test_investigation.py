from __future__ import annotations

from decimal import Decimal

from ledgerflow_agent.investigation import ReconciliationInvestigator
from ledgerflow_agent.models import ModelReply, ScriptedModelClient, ToolCall
from ledgerflow_agent.schemas import Decision, ProposalSource

from .conftest import INVOICE_A, INVOICE_B, PAYMENT_ID, FakeCore, candidate, candidate_set


def propose(allocations, confidence=0.9, rationale="because") -> ModelReply:
    return ModelReply(text="", tool_calls=(ToolCall(
        id="call_1", name="propose_allocation",
        arguments={"allocations": allocations, "confidence": confidence,
                   "rationale": rationale}),))


async def investigate(core: FakeCore, model: ScriptedModelClient):
    client = core.client()
    try:
        return await ReconciliationInvestigator(client, model).investigate(PAYMENT_ID)
    finally:
        await client.aclose()


async def test_confident_candidate_set_never_reaches_the_model():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "5000.00", "5000.00", 0.99,
                  ["EXACT_OUTSTANDING_MATCH", "REFERENCE_CONTAINS_INVOICE_NUMBER"]),
        ambiguous=False))
    model = ScriptedModelClient([])

    proposal = await investigate(core, model)

    assert model.calls == []
    assert proposal.source is ProposalSource.DETERMINISTIC
    assert proposal.decision is Decision.APPLY
    assert proposal.allocations[0].amount == Decimal("5000.00")
    assert "/api/payments/" not in "".join(core.paths_requested())


async def test_agent_resolves_an_ambiguous_pair_into_a_single_allocation():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "5000.00", "5000.00", 0.58, ["EXACT_OUTSTANDING_MATCH"]),
        candidate(INVOICE_B, "INV-2025-061", "5000.00", "5000.00", 0.55, ["EXACT_OUTSTANDING_MATCH"])))
    model = ScriptedModelClient([propose([{"invoice_id": INVOICE_A, "amount": "5000.00"}])])

    proposal = await investigate(core, model)

    assert proposal.source is ProposalSource.AGENT
    assert proposal.decision is Decision.APPLY
    assert [allocation.invoice_number for allocation in proposal.allocations] == ["INV-2025-054"]
    assert proposal.model_calls == 1


async def test_agent_may_split_one_payment_across_several_invoices():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "3000.00", "3000.00", 0.4, ["PARTIALLY_COVERS_PAYMENT"]),
        candidate(INVOICE_B, "INV-2025-061", "2000.00", "2000.00", 0.4, ["PARTIALLY_COVERS_PAYMENT"])))
    model = ScriptedModelClient([propose([
        {"invoice_id": INVOICE_A, "amount": "3000.00"},
        {"invoice_id": INVOICE_B, "amount": "2000.00"},
    ])])

    proposal = await investigate(core, model)

    assert proposal.decision is Decision.APPLY
    assert sum(allocation.amount for allocation in proposal.allocations) == Decimal("5000.00")


async def test_allocation_beyond_the_unapplied_payment_amount_is_rejected():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "4000.00", "4000.00", 0.4, ["PARTIALLY_COVERS_PAYMENT"]),
        candidate(INVOICE_B, "INV-2025-061", "4000.00", "4000.00", 0.4, ["PARTIALLY_COVERS_PAYMENT"])))
    model = ScriptedModelClient([propose([
        {"invoice_id": INVOICE_A, "amount": "4000.00"},
        {"invoice_id": INVOICE_B, "amount": "4000.00"},
    ])])

    proposal = await investigate(core, model)

    assert proposal.decision is Decision.ESCALATE
    assert proposal.allocations == []
    assert "only 5000.00" in proposal.rejected_reasons[0]


async def test_allocation_beyond_an_invoice_outstanding_amount_is_rejected():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "1200.00", "1200.00", 0.4, ["PARTIAL_PAYMENT_OF_INVOICE"])))
    model = ScriptedModelClient([propose([{"invoice_id": INVOICE_A, "amount": "5000.00"}])])

    proposal = await investigate(core, model)

    assert proposal.decision is Decision.ESCALATE
    assert "outstanding" in proposal.rejected_reasons[0]


async def test_allocation_to_an_invoice_outside_the_candidate_set_is_rejected():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "5000.00", "5000.00", 0.4, ["EXACT_OUTSTANDING_MATCH"])))
    model = ScriptedModelClient([propose([
        {"invoice_id": "99999999-9999-9999-9999-999999999999", "amount": "5000.00"}])])

    proposal = await investigate(core, model)

    assert proposal.decision is Decision.ESCALATE
    assert "not in the deterministic candidate set" in proposal.rejected_reasons[0]


async def test_agent_escalates_a_genuine_coin_flip():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "5000.00", "5000.00", 0.5, ["EXACT_OUTSTANDING_MATCH"]),
        candidate(INVOICE_B, "INV-2025-061", "5000.00", "5000.00", 0.5, ["EXACT_OUTSTANDING_MATCH"])))
    model = ScriptedModelClient([ModelReply(text="", tool_calls=(ToolCall(
        id="call_1", name="escalate",
        arguments={"rationale": "Two identical invoices; the reference names neither."}),))])

    proposal = await investigate(core, model)

    assert proposal.decision is Decision.ESCALATE
    assert proposal.rejected_reasons == []
    assert "identical" in proposal.rationale


async def test_agent_reads_capabilities_before_concluding():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "5000.00", "5000.00", 0.5, ["EXACT_OUTSTANDING_MATCH"])))
    model = ScriptedModelClient([
        ModelReply(text="Checking the bank line.", tool_calls=(ToolCall(
            id="call_1", name="search_transactions", arguments={"query": "CALDER"}),)),
        propose([{"invoice_id": INVOICE_A, "amount": "5000.00"}]),
    ])

    proposal = await investigate(core, model)

    assert proposal.decision is Decision.APPLY
    assert proposal.capability_calls == 1
    assert proposal.model_calls == 2
    assert "/api/transactions" in core.paths_requested()


async def test_unknown_capability_is_reported_to_the_model_rather_than_crashing():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "5000.00", "5000.00", 0.5, ["EXACT_OUTSTANDING_MATCH"])))
    model = ScriptedModelClient([
        ModelReply(text="", tool_calls=(ToolCall(id="call_1", name="delete_invoice",
                                                 arguments={"invoice_id": INVOICE_A}),)),
        propose([{"invoice_id": INVOICE_A, "amount": "5000.00"}]),
    ])

    proposal = await investigate(core, model)

    assert proposal.decision is Decision.APPLY
    assert "delete_invoice" not in "".join(core.paths_requested())


async def test_investigation_escalates_when_it_runs_past_the_step_limit():
    core = FakeCore(candidate_set(
        candidate(INVOICE_A, "INV-2025-054", "5000.00", "5000.00", 0.5, ["EXACT_OUTSTANDING_MATCH"])))
    dithering = ModelReply(text="", tool_calls=(ToolCall(
        id="call_1", name="get_invoice", arguments={"invoice_id": INVOICE_A}),))
    model = ScriptedModelClient([dithering] * 3)

    client = core.client()
    try:
        proposal = await ReconciliationInvestigator(client, model, max_iterations=3).investigate(
            PAYMENT_ID)
    finally:
        await client.aclose()

    assert proposal.decision is Decision.ESCALATE
    assert "step limit" in proposal.rationale
