package com.ledgerflow.core.transaction;

import java.util.List;

/**
 * How a counterparty has been treated in the past. Deliberately reports the raw distribution rather
 * than a recommended code: a counterparty categorized three different ways is evidence of ambiguity,
 * and collapsing that to a single "best" answer would hide the very thing worth investigating.
 */
public record CounterpartyHistoryResponse(String counterparty,
                                          long categorizedCount,
                                          boolean consistentlyCategorized,
                                          List<CounterpartyCategorization> codes,
                                          List<TransactionResponse> recentExamples) {

    public static CounterpartyHistoryResponse of(String counterparty, List<CounterpartyCategorization> codes,
                                                 List<TransactionResponse> recentExamples) {
        long total = codes.stream().mapToLong(CounterpartyCategorization::transactionCount).sum();
        return new CounterpartyHistoryResponse(counterparty, total, codes.size() == 1, codes, recentExamples);
    }
}
