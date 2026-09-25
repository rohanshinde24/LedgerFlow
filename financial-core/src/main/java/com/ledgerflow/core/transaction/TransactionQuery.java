package com.ledgerflow.core.transaction;

import java.time.LocalDate;
import java.util.UUID;

public record TransactionQuery(UUID businessId,
                               UUID accountId,
                               LocalDate from,
                               LocalDate to,
                               CategorizationStatus categorizationStatus,
                               String searchText) {
}
