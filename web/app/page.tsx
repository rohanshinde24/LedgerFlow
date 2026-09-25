import Link from "next/link";
import { WorkspaceNotice } from "@/components/WorkspaceNotice";
import {
  getCashFlow,
  getExpensesByCategory,
  getReceivables,
  listTransactions
} from "@/lib/financialCoreClient";
import { formatMoney, formatMonth } from "@/lib/format";
import { trailingYear } from "@/lib/reportingWindow";
import { loadWorkspace } from "@/lib/workspace";

export default async function OverviewPage() {
  const workspace = await loadWorkspace();
  if (workspace.kind !== "ready") {
    return <WorkspaceNotice state={workspace} />;
  }

  const businessId = workspace.business.id;
  const window = trailingYear();

  const [expenses, cashFlow, receivables, uncategorized] = await Promise.all([
    getExpensesByCategory({ businessId, ...window }),
    getCashFlow({ businessId, ...window }),
    getReceivables({ businessId, asOf: window.to }),
    listTransactions({ businessId, categorizationStatus: "UNCATEGORIZED", size: 1 })
  ]);

  const largestCategoryAmount = Math.max(
    ...expenses.categories.map((category) => Number(category.amount.amount)),
    0
  );

  return (
    <>
      <h2 className="page-title">{workspace.business.name}</h2>
      <p className="subtle">
        Trailing twelve months, {window.from} to {window.to}
      </p>

      <div className="metric-grid">
        <div className="card">
          <h2>Outstanding receivables</h2>
          <div className="metric-value">{formatMoney(receivables.outstandingAmount)}</div>
          <p className="subtle">{receivables.openInvoiceCount} unsettled invoices</p>
        </div>
        <div className="card">
          <h2>Overdue</h2>
          <div className="metric-value">{formatMoney(receivables.overdueAmount)}</div>
          <p className="subtle">
            {receivables.overdueInvoiceCount} invoices &middot;{" "}
            <Link href="/invoices?status=OPEN">Review</Link>
          </p>
        </div>
        <div className="card">
          <h2>Total expenses</h2>
          <div className="metric-value">{formatMoney(expenses.total)}</div>
          <p className="subtle">{expenses.categories.length} categories</p>
        </div>
        <div className="card">
          <h2>Awaiting categorization</h2>
          <div className="metric-value">{uncategorized.totalItems}</div>
          <p className="subtle">
            <Link href="/transactions?status=UNCATEGORIZED">Review transactions</Link>
          </p>
        </div>
      </div>

      <div className="split">
        <section className="card">
          <h2>Expenses by category</h2>
          <table>
            <thead>
              <tr>
                <th>Category</th>
                <th className="numeric">Amount</th>
                <th className="numeric">Count</th>
              </tr>
            </thead>
            <tbody>
              {expenses.categories.slice(0, 10).map((category) => (
                <tr key={category.chartOfAccountCode}>
                  <td>
                    {category.chartOfAccountName}
                    <div className="bar-track">
                      <div
                        className="bar-fill"
                        style={{ width: `${percentOf(Number(category.amount.amount), largestCategoryAmount)}%` }}
                      />
                    </div>
                  </td>
                  <td className="numeric">{formatMoney(category.amount)}</td>
                  <td className="numeric">{category.transactionCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section className="card">
          <h2>Monthly cash flow</h2>
          <table>
            <thead>
              <tr>
                <th>Month</th>
                <th className="numeric">In</th>
                <th className="numeric">Out</th>
                <th className="numeric">Net</th>
              </tr>
            </thead>
            <tbody>
              {cashFlow.periods.map((period) => (
                <tr key={period.month}>
                  <td>{formatMonth(period.month)}</td>
                  <td className="numeric positive">{formatMoney(period.inflow)}</td>
                  <td className="numeric negative">{formatMoney(period.outflow)}</td>
                  <td className={Number(period.net.amount) < 0 ? "numeric negative" : "numeric"}>
                    {formatMoney(period.net)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      </div>

      <section className="card" style={{ marginTop: 16 }}>
        <h2>Receivables aging</h2>
        <table>
          <thead>
            <tr>
              <th>Bucket</th>
              <th className="numeric">Outstanding</th>
              <th className="numeric">Invoices</th>
            </tr>
          </thead>
          <tbody>
            {receivables.aging.map((bucket) => (
              <tr key={bucket.label}>
                <td>{bucket.label}</td>
                <td className="numeric">{formatMoney(bucket.amount)}</td>
                <td className="numeric">{bucket.invoiceCount}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
    </>
  );
}

function percentOf(value: number, maximum: number): number {
  return maximum <= 0 ? 0 : Math.round((value / maximum) * 100);
}
