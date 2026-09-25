import Link from "next/link";
import { notFound } from "next/navigation";
import { EmptyState } from "@/components/EmptyState";
import { StatusBadge } from "@/components/StatusBadge";
import { getReconciliationCandidates } from "@/lib/financialCoreClient";
import { formatDate, formatMoney, humanizeEnum } from "@/lib/format";

export default async function ReconciliationCandidatesPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;

  let candidateSet;
  try {
    candidateSet = await getReconciliationCandidates(id);
  } catch {
    notFound();
  }

  return (
    <>
      <p className="subtle" style={{ marginTop: 24 }}>
        <Link href="/reconciliation">Reconciliation</Link> / candidates
      </p>
      <h2 className="page-title">{formatMoney(candidateSet.unappliedAmount)} unapplied</h2>
      <p className="subtle">
        {candidateSet.consideredInvoiceCount} invoices considered &middot; {candidateSet.candidates.length} ranked
        candidates{" "}
        {candidateSet.ambiguous ? <StatusBadge status="AMBIGUOUS" warning /> : <StatusBadge status="CONFIDENT" />}
      </p>

      <div className="card">
        {candidateSet.candidates.length === 0 ? (
          <EmptyState message="No invoice in the search window plausibly matches this payment." />
        ) : (
          <table>
            <thead>
              <tr>
                <th>Invoice</th>
                <th>Customer</th>
                <th>Due</th>
                <th>Signals</th>
                <th className="numeric">Outstanding</th>
                <th className="numeric">Suggested</th>
                <th className="numeric">Score</th>
              </tr>
            </thead>
            <tbody>
              {candidateSet.candidates.map((candidate) => (
                <tr key={candidate.invoiceId}>
                  <td>
                    <Link href={`/invoices/${candidate.invoiceId}`}>{candidate.invoiceNumber}</Link>
                  </td>
                  <td>{candidate.customerName}</td>
                  <td>{formatDate(candidate.dueDate)}</td>
                  <td>
                    {candidate.signals.map((signal) => (
                      <div key={signal} className="subtle">
                        {humanizeEnum(signal)}
                      </div>
                    ))}
                  </td>
                  <td className="numeric">{formatMoney(candidate.outstandingAmount)}</td>
                  <td className="numeric">{formatMoney(candidate.suggestedAmount)}</td>
                  <td className="numeric">{candidate.score.toFixed(2)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </>
  );
}
