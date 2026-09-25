import Link from "next/link";
import { EmptyState } from "@/components/EmptyState";
import { MoneyCell } from "@/components/MoneyCell";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { WorkspaceNotice } from "@/components/WorkspaceNotice";
import { listInvoices } from "@/lib/financialCoreClient";
import { formatDate } from "@/lib/format";
import { loadWorkspace } from "@/lib/workspace";
import type { InvoiceStatus } from "@/lib/types";

const STATUS_FILTERS: { label: string; value?: InvoiceStatus }[] = [
  { label: "All" },
  { label: "Open", value: "OPEN" },
  { label: "Partially paid", value: "PARTIALLY_PAID" },
  { label: "Paid", value: "PAID" }
];

const PAGE_SIZE = 50;

export default async function InvoicesPage({
  searchParams
}: {
  searchParams: Promise<{ status?: string; page?: string }>;
}) {
  const workspace = await loadWorkspace();
  if (workspace.kind !== "ready") {
    return <WorkspaceNotice state={workspace} />;
  }

  const params = await searchParams;
  const result = await listInvoices({
    businessId: workspace.business.id,
    status: params.status,
    page: Number(params.page ?? 0),
    size: PAGE_SIZE
  });

  return (
    <>
      <h2 className="page-title">Invoices</h2>
      <p className="subtle">{result.totalItems} invoices</p>

      <div className="filters">
        {STATUS_FILTERS.map((filter) => (
          <Link
            key={filter.label}
            className="filter-link"
            data-active={(params.status ?? undefined) === filter.value}
            href={filter.value ? `/invoices?status=${filter.value}` : "/invoices"}
          >
            {filter.label}
          </Link>
        ))}
      </div>

      <div className="card">
        {result.items.length === 0 ? (
          <EmptyState message="No invoices match this filter." />
        ) : (
          <table>
            <thead>
              <tr>
                <th>Invoice</th>
                <th>Customer</th>
                <th>Issued</th>
                <th>Due</th>
                <th>Status</th>
                <th className="numeric">Total</th>
                <th className="numeric">Outstanding</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((invoice) => (
                <tr key={invoice.id}>
                  <td>
                    <Link href={`/invoices/${invoice.id}`}>{invoice.invoiceNumber}</Link>
                  </td>
                  <td>{invoice.customerName}</td>
                  <td>{formatDate(invoice.issueDate)}</td>
                  <td>
                    {formatDate(invoice.dueDate)}
                    {invoice.overdue ? (
                      <div>
                        <StatusBadge status="OVERDUE" warning />
                      </div>
                    ) : null}
                  </td>
                  <td>
                    <StatusBadge status={invoice.status} />
                  </td>
                  <MoneyCell money={invoice.totalAmount} />
                  <MoneyCell money={invoice.outstandingAmount} />
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Pagination
        basePath="/invoices"
        query={{ status: params.status }}
        page={result.page}
        totalPages={result.totalPages}
      />
    </>
  );
}
