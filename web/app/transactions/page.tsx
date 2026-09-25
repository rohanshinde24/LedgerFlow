import Link from "next/link";
import { EmptyState } from "@/components/EmptyState";
import { MoneyCell } from "@/components/MoneyCell";
import { Pagination } from "@/components/Pagination";
import { StatusBadge } from "@/components/StatusBadge";
import { WorkspaceNotice } from "@/components/WorkspaceNotice";
import { listTransactions } from "@/lib/financialCoreClient";
import { formatDate } from "@/lib/format";
import { loadWorkspace } from "@/lib/workspace";
import type { CategorizationStatus } from "@/lib/types";

const STATUS_FILTERS: { label: string; value?: CategorizationStatus }[] = [
  { label: "All" },
  { label: "Uncategorized", value: "UNCATEGORIZED" },
  { label: "Needs review", value: "NEEDS_REVIEW" },
  { label: "Categorized", value: "CATEGORIZED" }
];

const PAGE_SIZE = 50;

export default async function TransactionsPage({
  searchParams
}: {
  searchParams: Promise<{ status?: string; page?: string; q?: string }>;
}) {
  const workspace = await loadWorkspace();
  if (workspace.kind !== "ready") {
    return <WorkspaceNotice state={workspace} />;
  }

  const params = await searchParams;
  const page = Number(params.page ?? 0);
  const result = await listTransactions({
    businessId: workspace.business.id,
    categorizationStatus: params.status,
    q: params.q,
    page,
    size: PAGE_SIZE
  });

  return (
    <>
      <h2 className="page-title">Transactions</h2>
      <p className="subtle">{result.totalItems} transactions</p>

      <div className="filters">
        {STATUS_FILTERS.map((filter) => (
          <Link
            key={filter.label}
            className="filter-link"
            data-active={(params.status ?? undefined) === filter.value}
            href={filter.value ? `/transactions?status=${filter.value}` : "/transactions"}
          >
            {filter.label}
          </Link>
        ))}
      </div>

      <div className="card">
        {result.items.length === 0 ? (
          <EmptyState message="No transactions match this filter." />
        ) : (
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Description</th>
                <th>Account</th>
                <th>Category</th>
                <th>Status</th>
                <th className="numeric">Amount</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((transaction) => (
                <tr key={transaction.id}>
                  <td>{formatDate(transaction.bookedDate)}</td>
                  <td>
                    {transaction.description}
                    {transaction.counterparty ? (
                      <div className="subtle">{transaction.counterparty}</div>
                    ) : null}
                  </td>
                  <td>{transaction.accountName}</td>
                  <td>
                    {transaction.chartOfAccountName ? (
                      <>
                        {transaction.chartOfAccountName}
                        <div className="subtle">{transaction.chartOfAccountCode}</div>
                      </>
                    ) : (
                      <span className="subtle">Unassigned</span>
                    )}
                  </td>
                  <td>
                    <StatusBadge
                      status={transaction.categorizationStatus}
                      warning={transaction.categorizationStatus !== "CATEGORIZED"}
                    />
                  </td>
                  <MoneyCell money={transaction.amount} />
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <Pagination
        basePath="/transactions"
        query={{ status: params.status, q: params.q }}
        page={result.page}
        totalPages={result.totalPages}
      />
    </>
  );
}
