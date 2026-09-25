package com.ledgerflow.core.reconciliation;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public final class CounterpartyNameSimilarity {

    private static final Set<String> LEGAL_SUFFIXES =
            Set.of("inc", "llc", "ltd", "co", "corp", "corporation", "company", "gmbh", "plc", "lp", "llp");

    private CounterpartyNameSimilarity() {
    }

    public static double score(String left, String right) {
        Set<String> leftTokens = tokenize(left);
        Set<String> rightTokens = tokenize(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        Set<String> union = new LinkedHashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                .filter(token -> !token.isBlank())
                .filter(token -> !LEGAL_SUFFIXES.contains(token))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }
}
