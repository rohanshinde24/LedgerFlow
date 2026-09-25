package com.ledgerflow.core.reference;

import com.ledgerflow.core.ledger.AccountCategory;

import java.util.UUID;

public record ChartOfAccountResponse(UUID id, String code, String name, AccountCategory category) {
}
