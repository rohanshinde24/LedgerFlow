"""Counterfactual cases probing the evidence-sufficiency policy boundary.

These are development cases, not a benchmark. Each one stubs the financial core in process with
fabricated evidence and drives the *real* model through the real investigator, so what is being
tested is the decision policy rather than a scripted reply.

The boundary under test:

    commit when direct corroborating evidence supports one explanation,
    abstain when several explanations remain and nothing retrieved distinguishes them.

Every case is stated in terms of what evidence exists, never in terms of the merchant or category
the model is expected to name. A case that can only be passed by recognising a vendor name would be
testing recall, not policy.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from dataclasses import dataclass, field
from typing import Any

import httpx

sys.path.insert(0, str(__import__("pathlib").Path(__file__).resolve().parents[1] / "agent-service"))

from ledgerflow_agent.core_client import FinancialCoreClient  # noqa: E402
from ledgerflow_agent.models.ollama_client import OllamaModelClient  # noqa: E402
from ledgerflow_agent.schemas import TransactionOutcome  # noqa: E402
from ledgerflow_agent.transaction_investigation import TransactionInvestigator  # noqa: E402

BUSINESS_ID = "11111111-1111-1111-1111-111111111111"
SUBJECT_ID = "33333333-3333-3333-3333-333333333333"
SIBLING_ID = "44444444-4444-4444-4444-444444444444"

ABSTAINING = {TransactionOutcome.REQUEST_CONTEXT, TransactionOutcome.ESCALATE}

CHART_OF_ACCOUNTS = [
    {"id": f"coa-{code}", "code": code, "name": name, "category": category}
    for code, name, category in [
        ("1000", "Business Checking", "ASSET"),
        ("1010", "Business Savings", "ASSET"),
        ("1200", "Accounts Receivable", "ASSET"),
        ("2000", "Credit Card Payable", "LIABILITY"),
        ("3000", "Owner Equity", "EQUITY"),
        ("4000", "Consulting Revenue", "REVENUE"),
        ("4100", "Retainer Revenue", "REVENUE"),
        ("6000", "Software Subscriptions", "EXPENSE"),
        ("6100", "Contractor Payments", "EXPENSE"),
        ("6200", "Payroll", "EXPENSE"),
        ("6300", "Rent", "EXPENSE"),
        ("6400", "Travel", "EXPENSE"),
        ("6500", "Meals and Entertainment", "EXPENSE"),
        ("6600", "Advertising", "EXPENSE"),
        ("6700", "Office Supplies", "EXPENSE"),
        ("6800", "Professional Fees", "EXPENSE"),
        ("6900", "Bank Fees", "EXPENSE"),
    ]
]


def transaction(transaction_id: str = SUBJECT_ID, amount: str = "-412.00",
                description: str = "CARD PURCHASE", counterparty: str = "Unknown Vendor",
                account_name: str = "Business Checking",
                booked: str = "2025-03-11") -> dict[str, Any]:
    return {
        "id": transaction_id, "businessId": BUSINESS_ID, "bookedDate": booked,
        "description": description, "counterparty": counterparty,
        "amount": {"amount": amount, "currency": "USD"},
        "accountId": "acct-1", "accountName": account_name,
        "chartOfAccountCode": None, "chartOfAccountName": None, "vendorName": None,
        "categorizationStatus": "UNCATEGORIZED", "categorizationSource": None,
        "externalRef": "DEV-0001",
    }


def history(counterparty: str, *codes: dict[str, Any]) -> dict[str, Any]:
    return {
        "counterparty": counterparty,
        "categorizedCount": sum(code["transactionCount"] for code in codes),
        "consistentlyCategorized": len(codes) == 1,
        "codes": list(codes),
        "recentExamples": [],
    }


def usage(code: str, name: str, count: int) -> dict[str, Any]:
    return {"chartOfAccountCode": code, "chartOfAccountName": name,
            "transactionCount": count, "lastSeen": "2025-03-01"}


@dataclass
class PolicyCase:
    name: str
    expectation: str  # "COMMIT" or "ABSTAIN"
    rationale: str    # why the policy says so, in evidence terms
    subject: dict[str, Any]
    counterparty_history: dict[str, Any]
    offsetting: list[dict[str, Any]] = field(default_factory=list)
    similar: list[dict[str, Any]] = field(default_factory=list)
    expected_code: str | None = None

    def handler(self):
        def handle(request: httpx.Request) -> httpx.Response:
            path = request.url.path
            if path == "/api/chart-of-accounts":
                return httpx.Response(200, json=CHART_OF_ACCOUNTS)
            if path.endswith("/counterparty-history"):
                return httpx.Response(200, json=self.counterparty_history)
            if path.endswith("/offsetting-candidates"):
                return httpx.Response(200, json=self.offsetting)
            if path.endswith("/similar"):
                return httpx.Response(200, json=self.similar)
            if path == f"/api/transactions/{SUBJECT_ID}":
                return httpx.Response(200, json=self.subject)
            if path == "/api/transactions":
                return httpx.Response(200, json={"items": [], "page": 0, "size": 50,
                                                 "totalItems": 0, "totalPages": 0})
            return httpx.Response(404, json={"message": f"no stub for {path}"})
        return handle


def _unknown_vendor(name: str, description: str, amount: str) -> PolicyCase:
    """No history and several plausible expense accounts. Nothing distinguishes them."""
    return PolicyCase(
        name=f"unknown vendor / no history / {name}",
        expectation="ABSTAIN",
        rationale="several expense accounts remain plausible and nothing retrieved separates them",
        subject=transaction(description=description, counterparty=name, amount=amount),
        counterparty_history=history(name),
    )


def _clean_transfer(name: str, amount: str, sibling_account: str, code: str) -> PolicyCase:
    """No counterparty history, but a balancing entry that validates. History is irrelevant here."""
    return PolicyCase(
        name=f"transfer / no history / verified offset / {sibling_account}",
        expectation="COMMIT",
        rationale="a validated offsetting entry is decisive on its own",
        subject=transaction(description=name, counterparty="Internal Transfer", amount=amount),
        counterparty_history=history("Internal Transfer"),
        offsetting=[transaction(SIBLING_ID, amount=amount.lstrip("-"),
                                description="TRANSFER IN", account_name=sibling_account)],
        expected_code=code,
    )


def _weak_transfer(name: str, amount: str, offset_amount: str, offset_date: str) -> PolicyCase:
    """A near miss: something is in the sibling account, but it does not balance this entry."""
    return PolicyCase(
        name=f"transfer / near match only / {name}",
        expectation="ABSTAIN",
        rationale="the candidate does not balance the subject, so it corroborates nothing",
        subject=transaction(description=name, counterparty="Internal Transfer", amount=amount),
        counterparty_history=history("Internal Transfer"),
        offsetting=[transaction(SIBLING_ID, amount=offset_amount, description="TRANSFER IN",
                                account_name="Business Savings", booked=offset_date)],
    )


def _thin_precedent(name: str, description: str, code: str, coa_name: str) -> PolicyCase:
    """Below the tier-1 threshold, so the model decides — but the precedent is unanimous."""
    return PolicyCase(
        name=f"thin but unanimous precedent / {name}",
        expectation="COMMIT",
        rationale="a precedent that uniquely supports one category is decisive",
        subject=transaction(description=description, counterparty=name, amount="-240.00"),
        counterparty_history=history(name, usage(code, coa_name, 2)),
        expected_code=code,
    )


CASES: list[PolicyCase] = [
    _unknown_vendor("Rivet & Co", "CARD PURCHASE RIVET AND CO", "-412.00"),
    _unknown_vendor("Halcyon Partners", "ACH DEBIT HALCYON PARTNERS", "-1850.00"),
    _unknown_vendor("Meridian Supply", "POS MERIDIAN SUPPLY 4471", "-96.20"),
    _unknown_vendor("Ashgrove Ltd", "CARD PURCHASE ASHGROVE LTD", "-733.45"),

    _clean_transfer("TRANSFER TO SAVINGS *9082", "-5000.00", "Business Savings", "1010"),
    _clean_transfer("TRANSFER TO CHECKING *4417", "-2500.00", "Business Checking", "1000"),
    _clean_transfer("ONLINE TRANSFER *3310", "-12000.00", "Business Savings", "1010"),
    _clean_transfer("MOVE FUNDS *2201", "-750.00", "Business Checking", "1000"),

    _weak_transfer("TRANSFER TO SAVINGS *5511", "-5000.00", "4300.00", "2025-03-11"),
    _weak_transfer("TRANSFER OUT *7788", "-1200.00", "1200.00", "2025-02-02"),
    _weak_transfer("TRANSFER TO SAVINGS *9001", "-880.00", "615.00", "2025-03-12"),

    _thin_precedent("Northwind Cloud", "NORTHWIND CLOUD MONTHLY", "6000", "Software Subscriptions"),
    _thin_precedent("Larkfield Legal", "LARKFIELD LEGAL RETAINER", "6800", "Professional Fees"),
    _thin_precedent("Beacon Print Co", "BEACON PRINT CO INVOICE", "6600", "Advertising"),
]


async def run_case(case: PolicyCase, model: OllamaModelClient) -> dict[str, Any]:
    client = FinancialCoreClient("http://core.test",
                                 transport=httpx.MockTransport(case.handler()))
    try:
        finding = await TransactionInvestigator(client, model).investigate(SUBJECT_ID)
    finally:
        await client.aclose()

    abstained = finding.outcome in ABSTAINING
    if case.expectation == "ABSTAIN":
        passed = abstained
    else:
        passed = not abstained and (case.expected_code is None
                                    or finding.chart_of_account_code == case.expected_code)
    return {
        "case": case.name,
        "expectation": case.expectation,
        "passed": passed,
        "outcome": finding.outcome.value,
        "code": finding.chart_of_account_code,
        "expected_code": case.expected_code,
        "capability_calls": finding.capability_calls,
        "capability_sequence": finding.capability_sequence,
        "model_calls": finding.model_calls,
        "correction_attempts": finding.correction_attempts,
        "rejected_reasons": finding.rejected_reasons,
        "confidence": finding.confidence,
        "rationale": finding.rationale,
    }


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="qwen2.5:7b")
    parser.add_argument("--ollama-url", default="http://localhost:11434")
    parser.add_argument("--out", default="/tmp/policy-cases.json")
    parser.add_argument("--only", help="run only cases whose name contains this substring")
    args = parser.parse_args()

    model = OllamaModelClient(args.model, args.ollama_url)
    cases = [case for case in CASES if not args.only or args.only in case.name]
    results = []
    for index, case in enumerate(cases, start=1):
        result = await run_case(case, model)
        results.append(result)
        flag = "pass" if result["passed"] else "FAIL"
        print(f"[{index}/{len(cases)}] {flag}  {result['expectation']:7} got "
              f"{result['outcome']:16} code={result['code']}  {case.name}",
              file=sys.stderr, flush=True)
        if result["rejected_reasons"]:
            print(f"         rejected: {'; '.join(result['rejected_reasons'])}",
                  file=sys.stderr, flush=True)
        print(f"         path: {' -> '.join(result['capability_sequence']) or '(none)'}",
              file=sys.stderr, flush=True)

    by_group = {}
    for case, result in zip(cases, results):
        group = case.name.split(" / ")[0]
        bucket = by_group.setdefault(group, {"passed": 0, "total": 0})
        bucket["total"] += 1
        bucket["passed"] += int(result["passed"])

    summary = {"passed": sum(r["passed"] for r in results), "total": len(results),
               "by_group": by_group, "results": results}
    with open(args.out, "w") as handle:
        json.dump(summary, handle, indent=2)
    print(json.dumps({k: v for k, v in summary.items() if k != "results"}, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
