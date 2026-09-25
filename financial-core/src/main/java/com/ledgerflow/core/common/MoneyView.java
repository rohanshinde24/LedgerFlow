package com.ledgerflow.core.common;

public record MoneyView(String amount, String currency) {

    public static MoneyView of(Money money) {
        return new MoneyView(money.amount().toPlainString(), money.currency().getCurrencyCode());
    }
}
