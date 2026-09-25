package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.invoice.InvoiceRepository;
import com.ledgerflow.core.payment.Payment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ReconciliationCandidateFinder {

    static final int ISSUE_WINDOW_DAYS_BEFORE = 180;
    static final int ISSUE_WINDOW_DAYS_AFTER = 7;
    static final int MAX_CANDIDATES = 10;
    static final double MIN_SCORE = 0.10;

    private static final BigDecimal AMOUNT_TOLERANCE_RATIO = new BigDecimal("0.02");
    private static final long DUE_DATE_PROXIMITY_DAYS = 45;

    private final InvoiceRepository invoiceRepository;
    private final InvoiceCombinationGenerator combinationGenerator;

    public ReconciliationCandidateFinder(InvoiceRepository invoiceRepository,
                                         InvoiceCombinationGenerator combinationGenerator) {
        this.invoiceRepository = invoiceRepository;
        this.combinationGenerator = combinationGenerator;
    }

    public ReconciliationCandidateSet findCandidates(Payment payment, Money unappliedAmount) {
        List<Invoice> settleable = invoiceRepository.findSettleableIssuedBetween(
                payment.getBusiness().getId(),
                payment.getReceivedDate().minusDays(ISSUE_WINDOW_DAYS_BEFORE),
                payment.getReceivedDate().plusDays(ISSUE_WINDOW_DAYS_AFTER));

        List<Invoice> considered = restrictToKnownCustomer(settleable, payment).stream()
                .filter(invoice -> invoice.getCurrency().equals(payment.getCurrency()))
                .filter(invoice -> invoice.getOutstandingAmount().isPositive())
                .toList();

        List<ReconciliationCandidate> candidates = considered.stream()
                .map(invoice -> scoreCandidate(invoice, payment, unappliedAmount))
                .filter(candidate -> candidate.score() >= MIN_SCORE)
                .sorted(Comparator.comparingDouble(ReconciliationCandidate::score).reversed()
                        .thenComparing(candidate -> candidate.invoice().getDueDate()))
                .limit(MAX_CANDIDATES)
                .toList();

        return ReconciliationCandidateSet.of(candidates,
                combinationGenerator.settlingExactly(considered, unappliedAmount),
                considered.size(), unappliedAmount);
    }

    private List<Invoice> restrictToKnownCustomer(List<Invoice> invoices, Payment payment) {
        if (payment.getCustomer() == null) {
            return invoices;
        }
        UUID customerId = payment.getCustomer().getId();
        List<Invoice> ownedByCustomer = invoices.stream()
                .filter(invoice -> invoice.getCustomer().getId().equals(customerId))
                .toList();
        return ownedByCustomer.isEmpty() ? invoices : ownedByCustomer;
    }

    private ReconciliationCandidate scoreCandidate(Invoice invoice, Payment payment, Money unappliedAmount) {
        List<String> signals = new ArrayList<>();
        double score = scoreAmount(invoice, unappliedAmount, signals)
                + scoreReference(invoice, payment, signals)
                + scoreCounterparty(invoice, payment, signals)
                + scoreDueDateProximity(invoice, payment, signals);

        Money suggestedAmount = unappliedAmount.min(invoice.getOutstandingAmount());
        return new ReconciliationCandidate(invoice, suggestedAmount, Math.min(score, 1.0), List.copyOf(signals));
    }

    private double scoreAmount(Invoice invoice, Money unappliedAmount, List<String> signals) {
        Money outstanding = invoice.getOutstandingAmount();
        if (outstanding.compareTo(unappliedAmount) == 0) {
            signals.add("EXACT_OUTSTANDING_MATCH");
            return 0.45;
        }
        if (invoice.getTotalAmount().compareTo(unappliedAmount) == 0) {
            signals.add("EXACT_TOTAL_MATCH");
            return 0.40;
        }
        if (withinTolerance(outstanding, unappliedAmount)) {
            signals.add("AMOUNT_WITHIN_TOLERANCE");
            return 0.25;
        }
        if (outstanding.isLessThan(unappliedAmount)) {
            signals.add("PARTIALLY_COVERS_PAYMENT");
            return 0.12;
        }
        signals.add("PARTIAL_PAYMENT_OF_INVOICE");
        return 0.08;
    }

    private boolean withinTolerance(Money outstanding, Money unappliedAmount) {
        BigDecimal difference = outstanding.minus(unappliedAmount).abs().amount();
        BigDecimal allowed = unappliedAmount.amount().multiply(AMOUNT_TOLERANCE_RATIO);
        return difference.compareTo(allowed) <= 0;
    }

    private double scoreReference(Invoice invoice, Payment payment, List<String> signals) {
        String reference = normalizeReference(payment.getReference());
        String invoiceNumber = normalizeReference(invoice.getInvoiceNumber());
        if (!invoiceNumber.isEmpty() && reference.contains(invoiceNumber)) {
            signals.add("REFERENCE_CONTAINS_INVOICE_NUMBER");
            return 0.30;
        }
        return 0.0;
    }

    private String normalizeReference(String value) {
        return value == null ? "" : value.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    private double scoreCounterparty(Invoice invoice, Payment payment, List<String> signals) {
        if (payment.getCustomer() != null && payment.getCustomer().getId().equals(invoice.getCustomer().getId())) {
            signals.add("SAME_CUSTOMER");
            return 0.15;
        }
        double similarity = CounterpartyNameSimilarity.score(payment.getPayerNameRaw(), invoice.getCustomer().getName());
        if (similarity >= 0.5) {
            signals.add("PAYER_NAME_SIMILARITY");
            return 0.15 * similarity;
        }
        return 0.0;
    }

    private double scoreDueDateProximity(Invoice invoice, Payment payment, List<String> signals) {
        long days = Math.abs(ChronoUnit.DAYS.between(invoice.getDueDate(), payment.getReceivedDate()));
        if (days > DUE_DATE_PROXIMITY_DAYS) {
            return 0.0;
        }
        signals.add("DUE_DATE_PROXIMITY");
        return 0.10 * (1.0 - (double) days / DUE_DATE_PROXIMITY_DAYS);
    }
}
