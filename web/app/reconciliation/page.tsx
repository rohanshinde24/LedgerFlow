import Link from "next/link";
import { EmptyState } from "@/components/EmptyState";
import { MoneyCell } from "@/components/MoneyCell";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { WorkspaceNotice } from "@/components/WorkspaceNotice";
import { listPayments } from "@/lib/financialCoreClient";
import { formatDate } from "@/lib/format";
import { loadWorkspace } from "@/lib/workspace";
import type { PaymentStatus } from "@/lib/types";

const STATUS_FILTERS: { label: string; value?: PaymentStatus }[] = [
  { label: "Unapplied", value: "UNAPPLIED" },
  { label: "Partially applied", value: "PARTIALLY_APPLIED" },
  { label: "Applied", value: "APPLIED" },
  { label: "All" }
];

const PAGE_SIZE = 50;

export default async function ReconciliationPage({
  searchParams
}: {
  searchParams: Promise<{ status?: string; page?: string }>;
}) {
  const workspace = await loadWorkspace();
  if (workspace.kind !== "ready") {
    return <WorkspaceNotice state={workspace} />;
  }

  const params = await searchParams;
  const status = params.status ?? "UNAPPLIED";
  const result = await listPayments({
    businessId: workspace.business.id,
    status: status === "ALL" ? undefined : status,
    page: Number(params.page ?? 0),
    size: PAGE_SIZE
  });

  return (
    <>
      <h2 className="page-title">Reconciliation</h2>
      <p className="subtle">
        {result.totalItems} payments &middot; candidate invoices are ranked deterministically before any agent is
        involved
      </p>

      <div className="filters">
        {STATUS_FILTERS.map((filter) => (
          <Link
            key={filter.label}
            className="filter-link"
            data-active={status === (filter.value ?? "ALL")}
            href={`/reconciliation?status=${filter.value ?? "ALL"}`}
          >
            {filter.label}
          </Link>
        ))}
      </div>

      <div className="card">
        {result.items.length === 0 ? (
          <EmptyState message="No payments match this filter." />
        ) : (
          <table>
            <thead>
              <tr>
                <th>Received</th>
                <th>Payer</th>
                <th>Reference</th>
                <th>Method</th>
                <th>Status</th>
                <th className="numeric">Amount</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {result.items.map((payment) => (
                <tr key={payment.id}>
                  <td>{formatDate(payment.receivedDate)}</td>
                  <td>
                    {payment.payerName ?? <span className="subtle">Unknown</span>}
                    {payment.customerName ? <div className="subtle">{payment.customerName}</div> : null}
                  </td>
                  <td>{payment.reference}</td>
                  <td>{payment.method}</td>
                  <td>
                    <StatusBadge status={payment.status} warning={payment.status === "UNAPPLIED"} />
                  </td>
                  <MoneyCell money={payment.amount} />
                  <td>
                    <Link href={`/reconciliation/${payment.id}`}>Candidates</Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Pagination
        basePath="/reconciliation"
        query={{ status }}
        page={result.page}
        totalPages={result.totalPages}
      />
    </>
  );
}
