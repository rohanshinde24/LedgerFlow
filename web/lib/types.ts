export interface MoneyView {
  amount: string;
  currency: string;
}

export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Business {
  id: string;
  name: string;
  legalName: string;
  currency: string;
}

export type CategorizationStatus = "UNCATEGORIZED" | "NEEDS_REVIEW" | "CATEGORIZED";
export type CategorizationSource = "IMPORT" | "RULE" | "AGENT" | "USER";

export interface TransactionSummary {
  id: string;
  bookedDate: string;
  description: string;
  counterparty: string | null;
  amount: MoneyView;
  accountId: string;
  accountName: string;
  chartOfAccountCode: string | null;
  chartOfAccountName: string | null;
  vendorName: string | null;
  categorizationStatus: CategorizationStatus;
  categorizationSource: CategorizationSource;
  externalRef: string | null;
}

export type InvoiceStatus = "DRAFT" | "OPEN" | "PARTIALLY_PAID" | "PAID" | "VOID";

export interface InvoiceSummary {
  id: string;
  invoiceNumber: string;
  customerId: string;
  customerName: string;
  issueDate: string;
  dueDate: string;
  subtotal: MoneyView;
  taxAmount: MoneyView;
  totalAmount: MoneyView;
  amountPaid: MoneyView;
  outstandingAmount: MoneyView;
  status: InvoiceStatus;
  overdue: boolean;
  memo: string | null;
}

export interface InvoiceDetail {
  invoice: InvoiceSummary;
  lines: {
    id: string;
    lineNumber: number;
    description: string;
    quantity: string;
    unitPrice: MoneyView;
    lineTotal: MoneyView;
  }[];
}

export type PaymentStatus = "UNAPPLIED" | "PARTIALLY_APPLIED" | "APPLIED";

export interface PaymentSummary {
  id: string;
  receivedDate: string;
  amount: MoneyView;
  method: "ACH" | "CHECK" | "CARD" | "WIRE";
  reference: string | null;
  payerName: string | null;
  customerId: string | null;
  customerName: string | null;
  transactionId: string | null;
  status: PaymentStatus;
}

export interface ReconciliationCandidateSet {
  paymentId: string;
  unappliedAmount: MoneyView;
  consideredInvoiceCount: number;
  ambiguous: boolean;
  candidates: {
    invoiceId: string;
    invoiceNumber: string;
    customerName: string;
    issueDate: string;
    dueDate: string;
    totalAmount: MoneyView;
    outstandingAmount: MoneyView;
    suggestedAmount: MoneyView;
    score: number;
    signals: string[];
  }[];
}

export interface ExpensesByCategoryReport {
  from: string;
  to: string;
  total: MoneyView;
  categories: {
    chartOfAccountCode: string;
    chartOfAccountName: string;
    amount: MoneyView;
    transactionCount: number;
  }[];
}

export interface CashFlowReport {
  from: string;
  to: string;
  periods: {
    month: string;
    inflow: MoneyView;
    outflow: MoneyView;
    net: MoneyView;
  }[];
}

export interface ReceivablesSummary {
  asOf: string;
  outstandingAmount: MoneyView;
  overdueAmount: MoneyView;
  openInvoiceCount: number;
  overdueInvoiceCount: number;
  aging: {
    label: string;
    amount: MoneyView;
    invoiceCount: number;
  }[];
}
