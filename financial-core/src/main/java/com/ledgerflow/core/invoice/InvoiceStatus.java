package com.ledgerflow.core.invoice;

public enum InvoiceStatus {
    DRAFT,
    OPEN,
    PARTIALLY_PAID,
    PAID,
    VOID;

    public boolean isSettleable() {
        return this == OPEN || this == PARTIALLY_PAID;
    }
}
