package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;

import java.util.List;

public record ReconciliationCandidate(Invoice invoice, Money suggestedAmount, double score, List<String> signals) {
}
