from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Awaitable, Callable

from .core_client import FinancialCoreClient
from .models import ToolSpec


class CapabilityError(RuntimeError):
    pass


@dataclass(frozen=True)
class Capability:
    name: str
    description: str
    input_schema: dict[str, Any]
    handler: Callable[..., Awaitable[Any]]


class CapabilityRegistry:
    """The complete set of actions a model may take. Every capability here is a read; there is no
    registered capability that writes, so a model cannot mutate financial state by any tool call it
    is able to emit."""

    def __init__(self, client: FinancialCoreClient, business_id: str) -> None:
        self._client = client
        self._business_id = business_id
        self._capabilities = {capability.name: capability for capability in self._build()}

    def tool_specs(self) -> list[ToolSpec]:
        return [ToolSpec(capability.name, capability.description, capability.input_schema)
                for capability in self._capabilities.values()]

    async def invoke(self, name: str, arguments: dict[str, Any]) -> str:
        capability = self._capabilities.get(name)
        if capability is None:
            raise CapabilityError(f"unknown capability: {name}")
        try:
            result = await capability.handler(**arguments)
        except TypeError as exc:
            raise CapabilityError(f"invalid arguments for {name}: {exc}") from exc
        return json.dumps(result, default=str)

    def _build(self) -> list[Capability]:
        return [
            Capability(
                name="get_invoice",
                description="Fetch one invoice with its line items, totals, and outstanding amount.",
                input_schema={
                    "type": "object",
                    "properties": {"invoice_id": {"type": "string", "description": "Invoice UUID."}},
                    "required": ["invoice_id"],
                },
                handler=self._client.get_invoice,
            ),
            Capability(
                name="list_customer_invoices",
                description=(
                    "List invoices for one customer. Use to check whether a payment plausibly "
                    "settles several invoices at once, or to spot duplicate invoices."
                ),
                input_schema={
                    "type": "object",
                    "properties": {
                        "customer_id": {"type": "string"},
                        "status": {
                            "type": "string",
                            "enum": ["DRAFT", "OPEN", "PARTIALLY_PAID", "PAID", "VOID"],
                        },
                    },
                    "required": ["customer_id"],
                },
                handler=self._list_customer_invoices,
            ),
            Capability(
                name="search_transactions",
                description=(
                    "Search bank transactions by free text, optionally within a date range. Use to "
                    "corroborate a payment against the bank line that produced it."
                ),
                input_schema={
                    "type": "object",
                    "properties": {
                        "query": {"type": "string", "description": "Free-text search."},
                        "date_from": {"type": "string", "description": "ISO date, inclusive."},
                        "date_to": {"type": "string", "description": "ISO date, inclusive."},
                    },
                    "required": ["query"],
                },
                handler=self._search_transactions,
            ),
        ]

    async def _list_customer_invoices(self, customer_id: str, status: str | None = None) -> Any:
        return await self._client.list_invoices(self._business_id, customer_id=customer_id,
                                                status=status)

    async def _search_transactions(self, query: str, date_from: str | None = None,
                                   date_to: str | None = None) -> Any:
        return await self._client.list_transactions(self._business_id, query=query,
                                                    date_from=date_from, date_to=date_to)


class TransactionCapabilityRegistry(CapabilityRegistry):
    """Reads for investigating one uncategorized transaction.

    Every capability is bound to that transaction, so the model never passes an identifier and
    cannot point the investigation at a different record.
    """

    def __init__(self, client: FinancialCoreClient, business_id: str, transaction_id: str) -> None:
        self._transaction_id = transaction_id
        super().__init__(client, business_id)

    def _build(self) -> list[Capability]:
        return [
            Capability(
                name="get_counterparty_history",
                description=(
                    "How this transaction's counterparty was categorized in the past, as the raw "
                    "distribution of codes plus recent examples. An empty result means the "
                    "counterparty has never been categorized before."
                ),
                input_schema={
                    "type": "object",
                    "properties": {
                        "example_limit": {"type": "integer", "minimum": 1, "maximum": 25},
                    },
                },
                handler=self._counterparty_history,
            ),
            Capability(
                name="find_offsetting_transactions",
                description=(
                    "Find transactions in the business's other accounts whose amount exactly "
                    "offsets this one within a window of days. A hit is the evidence an internal "
                    "transfer leaves behind."
                ),
                input_schema={
                    "type": "object",
                    "properties": {
                        "window_days": {"type": "integer", "minimum": 0, "maximum": 30},
                    },
                },
                handler=self._offsetting,
            ),
            Capability(
                name="find_similar_transactions",
                description=(
                    "Find nearby transactions with the same counterparty and the same amount. Use "
                    "to tell a genuine duplicate from a legitimately recurring charge."
                ),
                input_schema={
                    "type": "object",
                    "properties": {
                        "window_days": {"type": "integer", "minimum": 0, "maximum": 30},
                    },
                },
                handler=self._similar,
            ),
            Capability(
                name="search_transactions",
                description=(
                    "Search the business's transactions by free text, optionally within a date "
                    "range. Use to establish whether a charge recurs or stands alone."
                ),
                input_schema={
                    "type": "object",
                    "properties": {
                        "query": {"type": "string"},
                        "date_from": {"type": "string", "description": "ISO date, inclusive."},
                        "date_to": {"type": "string", "description": "ISO date, inclusive."},
                    },
                    "required": ["query"],
                },
                handler=self._search_transactions,
            ),
            Capability(
                name="list_chart_of_accounts",
                description="The chart of accounts. Any code you propose must appear here.",
                input_schema={"type": "object", "properties": {}},
                handler=self._chart_of_accounts,
            ),
        ]

    async def _counterparty_history(self, example_limit: int = 5) -> Any:
        return await self._client.get_counterparty_history(self._transaction_id, example_limit)

    async def _offsetting(self, window_days: int = 3) -> Any:
        return await self._client.get_offsetting_candidates(self._transaction_id, window_days)

    async def _similar(self, window_days: int = 7) -> Any:
        return await self._client.get_similar_transactions(self._transaction_id, window_days)

    async def _chart_of_accounts(self) -> Any:
        return await self._client.list_chart_of_accounts(self._business_id)
