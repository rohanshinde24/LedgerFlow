import Link from "next/link";
import { notFound } from "next/navigation";
import { StatusBadge } from "@/components/StatusBadge";
import { getInvoice } from "@/lib/financialCoreClient";
import { formatDate, formatMoney } from "@/lib/format";

export default async function InvoiceDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  let detail;
  try {
    detail = await getInvoice(id);
  } catch {
    notFound();
  }

  const { invoice, lines } = detail;

  return (
    <>
      <p className="subtle" style={{ marginTop: 24 }}>
        <Link href="/invoices">Invoices</Link> / {invoice.invoiceNumber}
      </p>
      <h2 className="page-title">{invoice.customerName}</h2>
      <p className="subtle">
        Issued {formatDate(invoice.issueDate)} &middot; due {formatDate(invoice.dueDate)}{" "}
        <StatusBadge status={invoice.status} />
        {invoice.overdue ? <StatusBadge status="OVERDUE" warning /> : null}
      </p>

      <div className="metric-grid">
        <div className="card">
          <h2>Total</h2>
          <div className="metric-value">{formatMoney(invoice.totalAmount)}</div>
        </div>
        <div className="card">
          <h2>Paid</h2>
          <div className="metric-value">{formatMoney(invoice.amountPaid)}</div>
        </div>
        <div className="card">
          <h2>Outstanding</h2>
          <div className="metric-value">{formatMoney(invoice.outstandingAmount)}</div>
        </div>
      </div>

      <section className="card">
        <h2>Line items</h2>
        <table>
          <thead>
            <tr>
              <th>#</th>
              <th>Description</th>
              <th className="numeric">Quantity</th>
              <th className="numeric">Unit price</th>
              <th className="numeric">Total</th>
            </tr>
          </thead>
          <tbody>
            {lines.map((line) => (
              <tr key={line.id}>
                <td>{line.lineNumber}</td>
                <td>{line.description}</td>
                <td className="numeric">{line.quantity}</td>
                <td className="numeric">{formatMoney(line.unitPrice)}</td>
                <td className="numeric">{formatMoney(line.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <td colSpan={4} className="numeric subtle">
                Subtotal
              </td>
              <td className="numeric">{formatMoney(invoice.subtotal)}</td>
            </tr>
            <tr>
              <td colSpan={4} className="numeric subtle">
                Tax
              </td>
              <td className="numeric">{formatMoney(invoice.taxAmount)}</td>
            </tr>
          </tfoot>
        </table>
      </section>

      {invoice.memo ? <p className="subtle">{invoice.memo}</p> : null}
    </>
  );
}
