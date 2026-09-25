package com.ledgerflow.core.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void roundsToCurrencyFractionDigitsUsingHalfEven() {
        assertThat(Money.of("10.005", USD).amount()).isEqualByComparingTo("10.00");
        assertThat(Money.of("10.015", USD).amount()).isEqualByComparingTo("10.02");
    }

    @Test
    void addsAndSubtractsWithoutFloatingPointDrift() {
        Money total = Money.of("0.10", USD).plus(Money.of("0.20", USD));

        assertThat(total.amount()).isEqualByComparingTo("0.30");
        assertThat(total.minus(Money.of("0.30", USD)).isZero()).isTrue();
    }

    @Test
    void multipliesAndRoundsToCurrencyPrecision() {
        Money lineTotal = Money.of("133.33", USD).multipliedBy(new BigDecimal("3"));

        assertThat(lineTotal.amount()).isEqualByComparingTo("399.99");
    }

    @Test
    void rejectsArithmeticAcrossCurrencies() {
        assertThatThrownBy(() -> Money.of("10.00", USD).plus(Money.of("10.00", EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void comparesByAmount() {
        assertThat(Money.of("10.00", USD).isGreaterThan(Money.of("9.99", USD))).isTrue();
        assertThat(Money.of("10.00", USD).min(Money.of("9.99", USD)).amount()).isEqualByComparingTo("9.99");
    }
}
