package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.common.Money;

import java.util.List;

public record ReconciliationCandidateSet(List<ReconciliationCandidate> candidates,
                                         List<InvoiceCombination> exactCombinations,
                                         int consideredInvoiceCount,
                                         Money unappliedAmount,
                                         boolean ambiguous) {

    private static final double CONFIDENT_SCORE = 0.60;
    private static final double AMBIGUITY_MARGIN = 0.08;

    public static ReconciliationCandidateSet of(List<ReconciliationCandidate> candidates,
                                                List<InvoiceCombination> exactCombinations,
                                                int consideredInvoiceCount, Money unappliedAmount) {
        return new ReconciliationCandidateSet(candidates, exactCombinations, consideredInvoiceCount, unappliedAmount,
                isAmbiguous(candidates, exactCombinations));
    }

    private static boolean isAmbiguous(List<ReconciliationCandidate> candidates,
                                       List<InvoiceCombination> exactCombinations) {
        if (candidates.isEmpty()) {
            return true;
        }
        // A multi-invoice settlement that sums exactly is a rival explanation for the same money, so
        // its existence is ambiguity in itself however well a single invoice scores.
        if (!exactCombinations.isEmpty()) {
            return true;
        }
        double top = candidates.get(0).score();
        if (top < CONFIDENT_SCORE) {
            return true;
        }
        return candidates.size() > 1 && top - candidates.get(1).score() < AMBIGUITY_MARGIN;
    }

    public ReconciliationCandidate topCandidate() {
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
