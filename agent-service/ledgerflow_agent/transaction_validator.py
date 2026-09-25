from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

from .schemas import ABSTAINING_OUTCOMES, TransactionOutcome


class FindingRejected(Exception):
    """A conclusion that could not be confirmed from the financial record.

    Rejected findings are escalated, never repaired. A silently corrected conclusion would make the
    measured accuracy of the investigation meaningless.
    """

    def __init__(self, reasons: list[str]) -> None:
        super().__init__("; ".join(reasons))
        self.reasons = reasons


@dataclass(frozen=True)
class ValidationContext:
    """Everything the validator needs, read from the core rather than taken from the model."""

    transaction: dict[str, Any]
    chart_of_accounts: list[dict[str, Any]]
    offsetting_candidates: list[dict[str, Any]] = field(default_factory=list)
    similar_transactions: list[dict[str, Any]] = field(default_factory=list)

    @property
    def offsetting_candidate_ids(self) -> set[str]:
        return {candidate["id"] for candidate in self.offsetting_candidates}

    @property
    def similar_transaction_ids(self) -> set[str]:
        return {candidate["id"] for candidate in self.similar_transactions}


def derive_transfer_account(related_transaction_id: str,
                            context: ValidationContext) -> str | None:
    """The account code for an internal transfer is not a judgement call: the money went to the
    account the balancing entry sits in. Each bank account has one asset entry of the same name in
    the chart of accounts, so the code is read off rather than guessed at."""
    counterpart = next((candidate for candidate in context.offsetting_candidates
                        if candidate["id"] == related_transaction_id), None)
    if counterpart is None:
        return None
    entry = next((entry for entry in context.chart_of_accounts
                  if entry.get("category") == "ASSET"
                  and entry.get("name") == counterpart.get("accountName")), None)
    return entry["code"] if entry else None


#: An inflow booked to one of these is normal; an outflow booked to one is not, and vice versa.
_INFLOW_ONLY_CATEGORIES = {"REVENUE"}
_OUTFLOW_ONLY_CATEGORIES = {"EXPENSE"}

_OUTCOMES_REQUIRING_ACCOUNT = {
    TransactionOutcome.CATEGORIZE,
    TransactionOutcome.RECURRING_EXPENSE,
    TransactionOutcome.MATCH_REFUND,
}

_OUTCOMES_REQUIRING_RELATION = {
    TransactionOutcome.INTERNAL_TRANSFER,
    TransactionOutcome.DUPLICATE,
}


def validate_finding(outcome: TransactionOutcome, chart_of_account_code: str | None,
                     related_transaction_id: str | None, context: ValidationContext) -> None:
    if outcome in ABSTAINING_OUTCOMES:
        return

    reasons: list[str] = []
    entry = _find_account(chart_of_account_code, context.chart_of_accounts)

    if outcome in _OUTCOMES_REQUIRING_ACCOUNT and chart_of_account_code is None:
        reasons.append(f"{outcome} requires a chart_of_account_code")
    if chart_of_account_code is not None and entry is None:
        reasons.append(f"unknown chart of account code: {chart_of_account_code}")
    if outcome in _OUTCOMES_REQUIRING_RELATION and related_transaction_id is None:
        reasons.append(f"{outcome} requires a related_transaction_id")

    reasons.extend(_relation_reasons(outcome, related_transaction_id, context))
    if entry is not None:
        reasons.extend(_account_reasons(outcome, entry, context.transaction))

    if reasons:
        raise FindingRejected(reasons)


def _relation_reasons(outcome: TransactionOutcome, related_transaction_id: str | None,
                      context: ValidationContext) -> list[str]:
    if related_transaction_id is None:
        return []
    if related_transaction_id == context.transaction["id"]:
        return ["a transaction cannot be related to itself"]
    if outcome is TransactionOutcome.INTERNAL_TRANSFER:
        if related_transaction_id not in context.offsetting_candidate_ids:
            return ["the named transaction does not offset this one in a sibling account"]
        return _balance_reasons(related_transaction_id, context)
    if outcome is TransactionOutcome.DUPLICATE:
        if related_transaction_id not in context.similar_transaction_ids:
            return ["the named transaction does not share this counterparty and amount"]
        return _same_charge_reasons(related_transaction_id, context)
    return []


def _amount(record: dict[str, Any]) -> tuple[Decimal, str]:
    money = record["amount"]
    return Decimal(money["amount"]), money["currency"]


def _balance_reasons(related_transaction_id: str, context: ValidationContext) -> list[str]:
    """Membership in the offsetting set is a claim made by a query. Whether the money actually
    balances is arithmetic, and it is re-derived here rather than delegated, so widening that query
    can never widen what counts as a transfer."""
    counterpart = next(candidate for candidate in context.offsetting_candidates
                       if candidate["id"] == related_transaction_id)
    subject_amount, subject_currency = _amount(context.transaction)
    other_amount, other_currency = _amount(counterpart)

    if subject_currency != other_currency:
        return [f"a transfer cannot cross currencies: {subject_currency} against {other_currency}"]
    if other_amount != -subject_amount:
        return [f"the named transaction does not balance this one: {other_amount} against "
                f"{subject_amount}"]
    return []


def _same_charge_reasons(related_transaction_id: str, context: ValidationContext) -> list[str]:
    counterpart = next(candidate for candidate in context.similar_transactions
                       if candidate["id"] == related_transaction_id)
    subject_amount, subject_currency = _amount(context.transaction)
    other_amount, other_currency = _amount(counterpart)

    if (other_amount, other_currency) != (subject_amount, subject_currency):
        return [f"a duplicate must be the same charge: {other_amount} {other_currency} against "
                f"{subject_amount} {subject_currency}"]
    return []


def _account_reasons(outcome: TransactionOutcome, entry: dict[str, Any],
                     transaction: dict[str, Any]) -> list[str]:
    category = entry.get("category")
    amount = Decimal(transaction["amount"]["amount"])

    if outcome is TransactionOutcome.INTERNAL_TRANSFER and category != "ASSET":
        # Invariant 8: moving money between own accounts changes no revenue or expense.
        return [f"an internal transfer must book to an ASSET account, not {category}"]

    if outcome is TransactionOutcome.MATCH_REFUND:
        # A refund is an inflow that reverses an earlier expense, so it legitimately books to an
        # expense account against the usual sign rule.
        if category != "EXPENSE":
            return [f"a refund must reverse an EXPENSE account, not {category}"]
        return []

    if amount > 0 and category in _OUTFLOW_ONLY_CATEGORIES:
        return [f"money received cannot book to an {category} account"]
    if amount < 0 and category in _INFLOW_ONLY_CATEGORIES:
        return [f"money paid out cannot book to a {category} account"]
    return []


def _find_account(code: str | None, chart_of_accounts: list[dict[str, Any]]) -> dict[str, Any] | None:
    if code is None:
        return None
    return next((entry for entry in chart_of_accounts if entry.get("code") == code), None)
