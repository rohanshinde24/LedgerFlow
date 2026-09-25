from __future__ import annotations

from decimal import Decimal, InvalidOperation
from typing import Any

from .schemas import Allocation, CandidateSet


class ProposalRejected(Exception):
    def __init__(self, reasons: list[str]) -> None:
        super().__init__("; ".join(reasons))
        self.reasons = reasons


def validate_allocations(raw_allocations: Any, candidate_set: CandidateSet) -> list[Allocation]:
    """Deterministically check a model's proposed allocations against the candidate set.

    The model never sees this logic and cannot influence it. Anything that fails here is rejected
    and escalated rather than corrected, because a silently adjusted proposal would make the agent's
    measured accuracy meaningless.
    """
    reasons: list[str] = []

    if not isinstance(raw_allocations, list) or not raw_allocations:
        raise ProposalRejected(["allocations must be a non-empty list"])

    by_invoice_id = {candidate.invoice_id: candidate for candidate in candidate_set.candidates}
    allocations: list[Allocation] = []
    seen: set[str] = set()

    for index, entry in enumerate(raw_allocations):
        if not isinstance(entry, dict):
            reasons.append(f"allocation {index} is not an object")
            continue

        invoice_id = entry.get("invoice_id")
        candidate = by_invoice_id.get(invoice_id)
        if candidate is None:
            reasons.append(f"invoice {invoice_id} is not in the deterministic candidate set")
            continue
        if invoice_id in seen:
            reasons.append(f"invoice {invoice_id} allocated more than once")
            continue
        seen.add(invoice_id)

        try:
            amount = Decimal(str(entry.get("amount")))
        except (InvalidOperation, TypeError):
            reasons.append(f"invoice {invoice_id} has a non-numeric amount")
            continue

        if amount <= 0:
            reasons.append(f"invoice {invoice_id} allocated a non-positive amount")
            continue
        if amount > candidate.outstanding_amount.amount:
            reasons.append(
                f"invoice {invoice_id} allocated {amount} but only "
                f"{candidate.outstanding_amount.amount} is outstanding"
            )
            continue

        allocations.append(Allocation(invoice_id=invoice_id,
                                      invoice_number=candidate.invoice_number, amount=amount))

    if reasons:
        raise ProposalRejected(reasons)

    total = sum(allocation.amount for allocation in allocations)
    if total > candidate_set.unapplied_amount.amount:
        raise ProposalRejected([
            f"allocations total {total} but only {candidate_set.unapplied_amount.amount} "
            f"of the payment is unapplied"
        ])

    return allocations
