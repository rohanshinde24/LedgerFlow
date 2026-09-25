package com.ledgerflow.core.transaction;

import com.ledgerflow.core.common.MoneyView;

import java.time.LocalDate;
import java.util.UUID;

public record TransactionResponse(UUID id,
                                  UUID businessId,
                                  LocalDate bookedDate,
                                  String description,
                                  String counterparty,
                                  MoneyView amount,
                                  UUID accountId,
                                  String accountName,
                                  String chartOfAccountCode,
                                  String chartOfAccountName,
                                  String vendorName,
                                  CategorizationStatus categorizationStatus,
                                  CategorizationSource categorizationSource,
                                  String externalRef) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getBusiness().getId(),
                transaction.getBookedDate(),
                transaction.getDescription(),
                transaction.getCounterpartyRaw(),
                MoneyView.of(transaction.getAmount()),
                transaction.getAccount().getId(),
                transaction.getAccount().getName(),
                transaction.getCoaEntry() == null ? null : transaction.getCoaEntry().getCode(),
                transaction.getCoaEntry() == null ? null : transaction.getCoaEntry().getName(),
                transaction.getVendor() == null ? null : transaction.getVendor().getName(),
                transaction.getCategorizationStatus(),
                transaction.getCategorizationSource(),
                transaction.getExternalRef());
    }
}
