from __future__ import annotations

import json
from typing import Any

import httpx
import pytest

from ledgerflow_agent.core_client import FinancialCoreClient

BUSINESS_ID = "11111111-1111-1111-1111-111111111111"
PAYMENT_ID = "22222222-2222-2222-2222-222222222222"
INVOICE_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
INVOICE_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"


def money(amount: str, currency: str = "USD") -> dict:
    return {"amount": amount, "currency": currency}


def candidate(invoice_id: str, invoice_number: str, outstanding: str, suggested: str,
              score: float, signals: list[str], total: str | None = None) -> dict:
    return {
        "invoiceId": invoice_id,
        "invoiceNumber": invoice_number,
        "customerName": "Calder Manufacturing",
        "issueDate": "2025-11-20",
        "dueDate": "2025-12-20",
        "totalAmount": money(total or outstanding),
        "outstandingAmount": money(outstanding),
        "suggestedAmount": money(suggested),
        "score": score,
        "signals": signals,
    }


def candidate_set(*candidates: dict, unapplied: str = "5000.00", ambiguous: bool = True,
                  considered: int = 8) -> dict:
    return {
        "paymentId": PAYMENT_ID,
        "unappliedAmount": money(unapplied),
        "consideredInvoiceCount": considered,
        "ambiguous": ambiguous,
        "candidates": list(candidates),
    }


PAYMENT = {
    "id": PAYMENT_ID,
    "businessId": BUSINESS_ID,
    "receivedDate": "2025-12-22",
    "amount": money("5000.00"),
    "method": "ACH",
    "reference": "ACH DEPOSIT",
    "payerName": "CALDER MFG",
    "customerId": "cccccccc-cccc-cccc-cccc-cccccccccccc",
    "customerName": "Calder Manufacturing",
    "transactionId": None,
    "status": "UNAPPLIED",
}


TRANSACTION_ID = "33333333-3333-3333-3333-333333333333"
SIBLING_ID = "44444444-4444-4444-4444-444444444444"

CHART_OF_ACCOUNTS = [
    {"id": "coa-1000", "code": "1000", "name": "Business Checking", "category": "ASSET"},
    {"id": "coa-1010", "code": "1010", "name": "Business Savings", "category": "ASSET"},
    {"id": "coa-4000", "code": "4000", "name": "Consulting Revenue", "category": "REVENUE"},
    {"id": "coa-6000", "code": "6000", "name": "Software Subscriptions", "category": "EXPENSE"},
    {"id": "coa-6700", "code": "6700", "name": "Office Supplies", "category": "EXPENSE"},
]


def transaction(transaction_id: str = TRANSACTION_ID, amount: str = "-5000.00",
                description: str = "TRANSFER TO SAVINGS *9082",
                counterparty: str = "Internal Transfer",
                account_name: str = "Business Checking") -> dict:
    return {
        "id": transaction_id,
        "businessId": BUSINESS_ID,
        "bookedDate": "2025-01-20",
        "description": description,
        "counterparty": counterparty,
        "amount": money(amount),
        "accountId": "acct-checking",
        "accountName": account_name,
        "chartOfAccountCode": None,
        "chartOfAccountName": None,
        "vendorName": None,
        "categorizationStatus": "UNCATEGORIZED",
        "categorizationSource": None,
        "externalRef": "TXN-0001",
    }


def counterparty_history(counterparty: str = "Internal Transfer", *codes: dict,
                         examples: list[dict] | None = None) -> dict:
    entries = list(codes)
    return {
        "counterparty": counterparty,
        "categorizedCount": sum(entry["transactionCount"] for entry in entries),
        "consistentlyCategorized": len(entries) == 1,
        "codes": entries,
        "recentExamples": examples or [],
    }


def coa_usage(code: str, name: str, count: int, last_seen: str = "2025-11-01") -> dict:
    return {"chartOfAccountCode": code, "chartOfAccountName": name,
            "transactionCount": count, "lastSeen": last_seen}


class FakeCore:
    """In-process stand-in for the financial core. Records every request so tests can assert which
    capabilities the agent actually reached for."""

    def __init__(self, candidates: dict | None = None, payment: dict | None = None,
                 txn: dict | None = None) -> None:
        self.candidate_set = candidates
        self.payment = payment or PAYMENT
        self.transaction = txn or transaction()
        self.counterparty_history = counterparty_history()
        self.offsetting: list[dict] = []
        self.similar: list[dict] = []
        self.chart_of_accounts = CHART_OF_ACCOUNTS
        self.routes: dict[str, Any] = {}
        self.requests: list[httpx.Request] = []

    def handle(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(request)
        path = request.url.path

        if path == "/api/reconciliation/candidates":
            return httpx.Response(200, json=self.candidate_set)
        if path == f"/api/payments/{PAYMENT_ID}":
            return httpx.Response(200, json=self.payment)
        if path in self.routes:
            return httpx.Response(200, json=self.routes[path])
        if path == "/api/chart-of-accounts":
            return httpx.Response(200, json=self.chart_of_accounts)
        if path.endswith("/counterparty-history"):
            return httpx.Response(200, json=self.counterparty_history)
        if path.endswith("/offsetting-candidates"):
            return httpx.Response(200, json=self.offsetting)
        if path.endswith("/similar"):
            return httpx.Response(200, json=self.similar)
        if path == f"/api/transactions/{self.transaction['id']}":
            return httpx.Response(200, json=self.transaction)
        if path.startswith("/api/invoices/"):
            return httpx.Response(200, json={"invoice": {"id": path.rsplit("/", 1)[-1]},
                                             "lines": []})
        if path in {"/api/invoices", "/api/transactions"}:
            return httpx.Response(200, json={"items": [], "page": 0, "size": 50,
                                             "totalItems": 0, "totalPages": 0})
        return httpx.Response(404, json={"message": f"no stub for {path}"})

    def paths_requested(self) -> list[str]:
        return [request.url.path for request in self.requests]

    def client(self) -> FinancialCoreClient:
        return FinancialCoreClient("http://core.test",
                                   transport=httpx.MockTransport(self.handle))


@pytest.fixture
def fake_core():
    return FakeCore


def tool_result_payloads(conversation) -> list[Any]:
    payloads = []
    for turn in conversation.turns:
        if turn["role"] == "tool":
            payloads.extend(json.loads(result.content) for result in turn["results"])
    return payloads
