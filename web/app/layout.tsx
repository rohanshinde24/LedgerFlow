import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "LedgerFlow",
  description: "Agentic financial operations for small businesses"
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <div className="shell">
          <header className="masthead">
            <h1>
              <Link href="/">LedgerFlow</Link>
            </h1>
            <nav>
              <Link href="/">Overview</Link>
              <Link href="/transactions">Transactions</Link>
              <Link href="/invoices">Invoices</Link>
              <Link href="/reconciliation">Reconciliation</Link>
            </nav>
          </header>
          <main>{children}</main>
        </div>
      </body>
    </html>
  );
}
