package com.ledgerflow.core.common;

import java.util.Currency;

public class CurrencyMismatchException extends InvariantViolationException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Currency mismatch: expected %s but was %s"
                .formatted(expected.getCurrencyCode(), actual.getCurrencyCode()));
    }
}
