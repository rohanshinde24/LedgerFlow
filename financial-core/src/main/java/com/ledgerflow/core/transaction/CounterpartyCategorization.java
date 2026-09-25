package com.ledgerflow.core.transaction;

import java.time.LocalDate;

public record CounterpartyCategorization(String chartOfAccountCode,
                                         String chartOfAccountName,
                                         long transactionCount,
                                         LocalDate lastSeen) {
}
