from __future__ import annotations

from decimal import Decimal

from pydantic import BaseModel


class Money(BaseModel):
    amount: Decimal
    currency: str

    def __str__(self) -> str:
        return f"{self.amount} {self.currency}"


def parse_money(raw: dict) -> Money:
    # The core serializes amounts as decimal strings precisely so they never pass through a float.
    return Money(amount=Decimal(str(raw["amount"])), currency=raw["currency"])
