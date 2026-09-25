from __future__ import annotations

import json

import pytest

from ledgerflow_agent.capabilities import CapabilityError, CapabilityRegistry
from ledgerflow_agent.core_client import FinancialCoreClient

from .conftest import BUSINESS_ID, INVOICE_A, FakeCore, candidate, candidate_set


@pytest.fixture
async def registry():
    core = FakeCore(candidate_set(candidate(INVOICE_A, "INV-1", "1.00", "1.00", 0.5, [])))
    client = core.client()
    yield CapabilityRegistry(client, BUSINESS_ID), core
    await client.aclose()


async def test_registry_exposes_only_reads(registry):
    capability_registry, _ = registry
    names = {spec.name for spec in capability_registry.tool_specs()}

    assert names == {"get_invoice", "list_customer_invoices", "search_transactions"}


def test_core_client_exposes_no_mutating_verb():
    # The read-only guarantee is structural: if no method can issue anything but GET, no prompt can
    # talk the agent into a write.
    public_methods = [name for name in dir(FinancialCoreClient)
                      if not name.startswith("_") and name != "aclose"]

    assert all(name.startswith(("get_", "list_")) for name in public_methods), public_methods


async def test_capability_scopes_queries_to_the_configured_business(registry):
    capability_registry, core = registry

    await capability_registry.invoke("search_transactions", {"query": "CALDER"})

    assert core.requests[-1].url.params["businessId"] == BUSINESS_ID


async def test_unknown_capability_is_rejected(registry):
    capability_registry, _ = registry

    with pytest.raises(CapabilityError, match="unknown capability"):
        await capability_registry.invoke("wire_funds", {"amount": "1000.00"})


async def test_bad_arguments_are_rejected_rather_than_passed_through(registry):
    capability_registry, _ = registry

    with pytest.raises(CapabilityError, match="invalid arguments"):
        await capability_registry.invoke("get_invoice", {"wrong_name": INVOICE_A})


async def test_capability_results_are_json_for_the_model(registry):
    capability_registry, _ = registry

    result = await capability_registry.invoke("get_invoice", {"invoice_id": INVOICE_A})

    assert json.loads(result)["invoice"]["id"] == INVOICE_A
