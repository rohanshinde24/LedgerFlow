from __future__ import annotations

from decimal import Decimal
from enum import StrEnum

from pydantic import BaseModel, Field

from .money import Money


class Decision(StrEnum):
    APPLY = "APPLY"
    ESCALATE = "ESCALATE"


class ProposalSource(StrEnum):
    DETERMINISTIC = "DETERMINISTIC"
    AGENT = "AGENT"


class Allocation(BaseModel):
    invoice_id: str
    invoice_number: str
    amount: Decimal


class CandidateView(BaseModel):
    invoice_id: str
    invoice_number: str
    customer_name: str
    issue_date: str
    due_date: str
    total_amount: Money
    outstanding_amount: Money
    suggested_amount: Money
    score: float
    signals: list[str]


class CandidateSet(BaseModel):
    payment_id: str
    unapplied_amount: Money
    considered_invoice_count: int
    ambiguous: bool
    candidates: list[CandidateView]


class ReconciliationProposal(BaseModel):
    """A proposal, never a mutation. P2 turns an approved proposal into a financial operation."""

    payment_id: str
    decision: Decision
    allocations: list[Allocation] = Field(default_factory=list)
    confidence: float
    rationale: str
    source: ProposalSource
    model_name: str | None = None
    considered_invoice_count: int = 0
    candidate_count: int = 0
    model_calls: int = 0
    capability_calls: int = 0
    rejected_reasons: list[str] = Field(default_factory=list)


class InvestigateRequest(BaseModel):
    payment_id: str


class TransactionOutcome(StrEnum):
    """What a transaction turned out to represent. Deliberately wider than a category, because the
    interesting failures are the ones a category alone cannot express."""

    CATEGORIZE = "CATEGORIZE"
    INTERNAL_TRANSFER = "INTERNAL_TRANSFER"
    MATCH_REFUND = "MATCH_REFUND"
    MATCH_PAYMENT = "MATCH_PAYMENT"
    RECURRING_EXPENSE = "RECURRING_EXPENSE"
    DUPLICATE = "DUPLICATE"
    REQUEST_CONTEXT = "REQUEST_CONTEXT"
    ESCALATE = "ESCALATE"


#: Outcomes that assert nothing about the transaction and so need no deterministic confirmation.
ABSTAINING_OUTCOMES = {TransactionOutcome.REQUEST_CONTEXT, TransactionOutcome.ESCALATE}


class TransactionFinding(BaseModel):
    """The agent's conclusion about one transaction. Like a reconciliation proposal it is inert:
    nothing here has been written, and every non-abstaining outcome has passed deterministic
    validation before it appears."""

    transaction_id: str
    outcome: TransactionOutcome
    chart_of_account_code: str | None = None
    related_transaction_id: str | None = None
    confidence: float
    rationale: str
    question: str | None = None
    source: ProposalSource
    model_name: str | None = None
    evidence: list[str] = Field(default_factory=list)
    model_calls: int = 0
    capability_calls: int = 0
    capability_sequence: list[str] = Field(default_factory=list)
    rejected_reasons: list[str] = Field(default_factory=list)
    correction_attempts: int = 0
    input_tokens: int = 0
    output_tokens: int = 0


class InvestigateTransactionRequest(BaseModel):
    transaction_id: str
