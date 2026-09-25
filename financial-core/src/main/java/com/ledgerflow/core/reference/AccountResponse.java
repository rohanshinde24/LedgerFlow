package com.ledgerflow.core.reference;

import com.ledgerflow.core.common.MoneyView;
import com.ledgerflow.core.ledger.AccountType;

import java.util.UUID;

public record AccountResponse(UUID id,
                              String name,
                              AccountType accountType,
                              String institution,
                              String accountNumberMask,
                              MoneyView openingBalance) {
}
