package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Enumerates the subsets of open invoices whose outstanding balances sum exactly to a payment's
 * unapplied amount. Subset-sum is arithmetic, so it is settled here rather than by a language model;
 * what remains for investigation is choosing among combinations that are all equally valid.
 */
@Service
public class InvoiceCombinationGenerator {

    static final int MAX_INVOICES_ENUMERATED = 14;
    static final int MAX_COMBINATION_SIZE = 4;
    static final int MAX_COMBINATIONS_RETURNED = 8;

    public List<InvoiceCombination> settlingExactly(List<Invoice> invoices, Money target) {
        if (!target.isPositive()) {
            return List.of();
        }
        List<Invoice> eligible = invoices.stream()
                .filter(invoice -> invoice.getCurrency().equals(target.currency()))
                .filter(invoice -> invoice.getOutstandingAmount().isPositive())
                .filter(invoice -> !invoice.getOutstandingAmount().isGreaterThan(target))
                .sorted(Comparator.comparing(Invoice::getDueDate).thenComparing(Invoice::getInvoiceNumber))
                .toList();

        // Truncating the input would silently hide valid settlements, so an oversized set reports none.
        if (eligible.isEmpty() || eligible.size() > MAX_INVOICES_ENUMERATED) {
            return List.of();
        }

        List<InvoiceCombination> combinations = new ArrayList<>();
        search(eligible, target, 0, Money.zero(target.currency()), new ArrayDeque<>(), combinations);
        return combinations.stream()
                .sorted(Comparator.comparingInt((InvoiceCombination c) -> c.invoices().size())
                        .thenComparing(c -> c.invoices().get(0).getDueDate()))
                .limit(MAX_COMBINATIONS_RETURNED)
                .toList();
    }

    private void search(List<Invoice> eligible, Money target, int index, Money runningTotal,
                        Deque<Invoice> selected, List<InvoiceCombination> combinations) {
        if (combinations.size() >= MAX_COMBINATIONS_RETURNED * 4) {
            return;
        }
        if (runningTotal.compareTo(target) == 0 && selected.size() > 1) {
            combinations.add(new InvoiceCombination(List.copyOf(selected), runningTotal));
            return;
        }
        if (index == eligible.size() || selected.size() == MAX_COMBINATION_SIZE
                || runningTotal.isGreaterThan(target)) {
            return;
        }
        for (int next = index; next < eligible.size(); next++) {
            Invoice invoice = eligible.get(next);
            Money advanced = runningTotal.plus(invoice.getOutstandingAmount());
            if (advanced.isGreaterThan(target)) {
                continue;
            }
            selected.addLast(invoice);
            search(eligible, target, next + 1, advanced, selected, combinations);
            selected.removeLast();
        }
    }
}
