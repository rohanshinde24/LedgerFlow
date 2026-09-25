import type {
  Business,
  CashFlowReport,
  ExpensesByCategoryReport,
  InvoiceDetail,
  InvoiceSummary,
  PagedResponse,
  PaymentSummary,
  ReceivablesSummary,
  ReconciliationCandidateSet,
  TransactionSummary
} from "./types";

const baseUrl = process.env.FINANCIAL_CORE_URL ?? "http://localhost:8080";

export class FinancialCoreUnavailableError extends Error {}

type QueryValue = string | number | boolean | undefined | null;

async function get<T>(path: string, query: Record<string, QueryValue> = {}): Promise<T> {
  const url = new URL(path, baseUrl);
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== "") {
      url.searchParams.set(key, String(value));
    }
  }

  let response: Response;
  try {
    response = await fetch(url, { cache: "no-store" });
  } catch (cause) {
    throw new FinancialCoreUnavailableError(`Cannot reach the financial core at ${baseUrl}`, { cause });
  }

  if (!response.ok) {
    throw new Error(`${path} responded ${response.status}: ${await response.text()}`);
  }
  return (await response.json()) as T;
}

export function listBusinesses(): Promise<Business[]> {
  return get<Business[]>("/api/businesses");
}

export async function primaryBusiness(): Promise<Business | null> {
  const businesses = await listBusinesses();
  return businesses[0] ?? null;
}

export function listTransactions(query: {
  businessId: string;
  categorizationStatus?: string;
  accountId?: string;
  from?: string;
  to?: string;
  q?: string;
  page?: number;
  size?: number;
}): Promise<PagedResponse<TransactionSummary>> {
  return get<PagedResponse<TransactionSummary>>("/api/transactions", query);
}

export function listInvoices(query: {
  businessId: string;
  status?: string;
  customerId?: string;
  page?: number;
  size?: number;
}): Promise<PagedResponse<InvoiceSummary>> {
  return get<PagedResponse<InvoiceSummary>>("/api/invoices", query);
}

export function getInvoice(id: string): Promise<InvoiceDetail> {
  return get<InvoiceDetail>(`/api/invoices/${id}`);
}

export function listPayments(query: {
  businessId: string;
  status?: string;
  page?: number;
  size?: number;
}): Promise<PagedResponse<PaymentSummary>> {
  return get<PagedResponse<PaymentSummary>>("/api/payments", query);
}

export function getReconciliationCandidates(paymentId: string): Promise<ReconciliationCandidateSet> {
  return get<ReconciliationCandidateSet>("/api/reconciliation/candidates", { paymentId });
}

export function getExpensesByCategory(query: {
  businessId: string;
  from: string;
  to: string;
}): Promise<ExpensesByCategoryReport> {
  return get<ExpensesByCategoryReport>("/api/reports/expenses-by-category", query);
}

export function getCashFlow(query: {
  businessId: string;
  from: string;
  to: string;
}): Promise<CashFlowReport> {
  return get<CashFlowReport>("/api/reports/cash-flow", query);
}

export function getReceivables(query: { businessId: string; asOf: string }): Promise<ReceivablesSummary> {
  return get<ReceivablesSummary>("/api/reports/receivables", query);
}
