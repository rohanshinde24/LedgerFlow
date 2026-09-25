from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException

from .config import load_settings
from .core_client import FinancialCoreClient, FinancialCoreError
from .investigation import ReconciliationInvestigator
from .models import build_model_client
from .schemas import (
    InvestigateRequest,
    InvestigateTransactionRequest,
    ReconciliationProposal,
    TransactionFinding,
)
from .transaction_investigation import TransactionInvestigator


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = load_settings()
    app.state.settings = settings
    app.state.core_client = FinancialCoreClient(settings.financial_core_url,
                                                settings.core_timeout_seconds)
    app.state.model_client = build_model_client(settings)
    yield
    await app.state.core_client.aclose()


app = FastAPI(title="LedgerFlow Agent Service", lifespan=lifespan)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/api/agent/reconciliation/investigate", response_model=ReconciliationProposal)
async def investigate(request: InvestigateRequest) -> ReconciliationProposal:
    investigator = ReconciliationInvestigator(app.state.core_client, app.state.model_client,
                                              app.state.settings.max_agent_iterations)
    try:
        return await investigator.investigate(request.payment_id)
    except FinancialCoreError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc


@app.post("/api/agent/transactions/investigate", response_model=TransactionFinding)
async def investigate_transaction(request: InvestigateTransactionRequest) -> TransactionFinding:
    investigator = TransactionInvestigator(app.state.core_client, app.state.model_client,
                                           app.state.settings.max_transaction_iterations)
    try:
        return await investigator.investigate(request.transaction_id)
    except FinancialCoreError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
