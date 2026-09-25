package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.common.MoneyView;
import com.ledgerflow.core.invoice.Invoice;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReconciliationCandidateSetResponse(UUID paymentId,
                                                 MoneyView unappliedAmount,
                                                 int consideredInvoiceCount,
                                                 boolean ambiguous,
                                                 List<Candidate> candidates,
                                                 List<ExactCombination> exactCombinations) {

    public record Candidate(UUID invoiceId,
                            String invoiceNumber,
                            String customerName,
                            LocalDate issueDate,
                            LocalDate dueDate,
                            MoneyView totalAmount,
                            MoneyView outstandingAmount,
                            MoneyView suggestedAmount,
                            double score,
                            List<String> signals) {
    }

    /** Invoices whose outstanding balances sum exactly to the unapplied amount. The arithmetic is
     * already settled; what remains is deciding whether this combination is what the payer intended. */
    public record ExactCombination(List<UUID> invoiceIds,
                                   List<String> invoiceNumbers,
                                   MoneyView total) {
    }

    public static ReconciliationCandidateSetResponse from(UUID paymentId, ReconciliationCandidateSet candidateSet) {
        List<Candidate> candidates = candidateSet.candidates().stream()
                .map(candidate -> new Candidate(
                        candidate.invoice().getId(),
                        candidate.invoice().getInvoiceNumber(),
                        candidate.invoice().getCustomer().getName(),
                        candidate.invoice().getIssueDate(),
                        candidate.invoice().getDueDate(),
                        MoneyView.of(candidate.invoice().getTotalAmount()),
                        MoneyView.of(candidate.invoice().getOutstandingAmount()),
                        MoneyView.of(candidate.suggestedAmount()),
                        candidate.score(),
                        candidate.signals()))
                .toList();
        List<ExactCombination> combinations = candidateSet.exactCombinations().stream()
                .map(combination -> new ExactCombination(
                        combination.invoices().stream().map(Invoice::getId).toList(),
                        combination.invoices().stream().map(Invoice::getInvoiceNumber).toList(),
                        MoneyView.of(combination.total())))
                .toList();
        return new ReconciliationCandidateSetResponse(paymentId, MoneyView.of(candidateSet.unappliedAmount()),
                candidateSet.consideredInvoiceCount(), candidateSet.ambiguous(), candidates, combinations);
    }
}
