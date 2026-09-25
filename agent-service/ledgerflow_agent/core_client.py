from __future__ import annotations

from typing import Any

import httpx


class FinancialCoreError(RuntimeError):
    pass


class FinancialCoreClient:
    """Read-only HTTP client for the financial core.

    Only GET is exposed. The agent reaches the core exclusively through this class, so no reasoning
    path can reach a mutating endpoint even if a model asks for one.
    """

    def __init__(self, base_url: str, timeout_seconds: float = 15.0,
                 transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._http = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=timeout_seconds,
                                       transport=transport)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get(self, path: str, **params: Any) -> Any:
        query = {key: value for key, value in params.items() if value is not None}
        try:
            response = await self._http.get(path, params=query)
        except httpx.HTTPError as exc:
            raise FinancialCoreError(f"cannot reach the financial core: {exc}") from exc
        if response.status_code >= 400:
            raise FinancialCoreError(f"{path} responded {response.status_code}: {response.text}")
        return response.json()

    async def get_payment(self, payment_id: str) -> dict:
        return await self._get(f"/api/payments/{payment_id}")

    async def list_payments(self, business_id: str, status: str | None = None,
                            page: int = 0, size: int = 50) -> dict:
        return await self._get("/api/payments", businessId=business_id, status=status,
                               page=page, size=size)

    async def get_reconciliation_candidates(self, payment_id: str) -> dict:
        return await self._get("/api/reconciliation/candidates", paymentId=payment_id)

    async def get_invoice(self, invoice_id: str) -> dict:
        return await self._get(f"/api/invoices/{invoice_id}")

    async def list_invoices(self, business_id: str, customer_id: str | None = None,
                            status: str | None = None, page: int = 0, size: int = 50) -> dict:
        return await self._get("/api/invoices", businessId=business_id, customerId=customer_id,
                               status=status, page=page, size=size)

    async def list_transactions(self, business_id: str, query: str | None = None,
                                date_from: str | None = None, date_to: str | None = None,
                                categorization_status: str | None = None,
                                page: int = 0, size: int = 50) -> dict:
        return await self._get("/api/transactions", businessId=business_id, q=query,
                               categorizationStatus=categorization_status,
                               **{"from": date_from, "to": date_to}, page=page, size=size)

    async def get_transaction(self, transaction_id: str) -> dict:
        return await self._get(f"/api/transactions/{transaction_id}")

    async def get_counterparty_history(self, transaction_id: str, example_limit: int = 5) -> dict:
        return await self._get(f"/api/transactions/{transaction_id}/counterparty-history",
                               exampleLimit=example_limit)

    async def get_offsetting_candidates(self, transaction_id: str, window_days: int = 3) -> list:
        return await self._get(f"/api/transactions/{transaction_id}/offsetting-candidates",
                               windowDays=window_days)

    async def get_similar_transactions(self, transaction_id: str, window_days: int = 7) -> list:
        return await self._get(f"/api/transactions/{transaction_id}/similar",
                               windowDays=window_days)

    async def list_chart_of_accounts(self, business_id: str) -> list:
        return await self._get("/api/chart-of-accounts", businessId=business_id)
