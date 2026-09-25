package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;

import java.util.List;

/** A set of invoices whose outstanding balances sum exactly to a payment's unapplied amount. */
public record InvoiceCombination(List<Invoice> invoices, Money total) {
}
