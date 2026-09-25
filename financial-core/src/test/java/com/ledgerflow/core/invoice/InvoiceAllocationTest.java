package com.ledgerflow.core.invoice;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.InvariantViolationException;
import com.ledgerflow.core.party.Customer;
import com.ledgerflow.core.testsupport.FinancialFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceAllocationTest {

    private static final LocalDate ISSUE_DATE = LocalDate.of(2025, 3, 1);

    private Invoice invoice;

    @BeforeEach
    void setUp() {
        Business business = FinancialFixtures.business();
        Customer customer = FinancialFixtures.customer(business, "Ardent Health Partners");
        invoice = FinancialFixtures.invoice(business, customer, "INV-2025-001", "5000.00", ISSUE_DATE);
    }

    @Test
    void staysOpenWhenNothingIsAllocated() {
        invoice.applyConfirmedAllocations(FinancialFixtures.usd("0.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
        assertThat(invoice.getOutstandingAmount()).isEqualTo(FinancialFixtures.usd("5000.00"));
    }

    @Test
    void becomesPartiallyPaidBelowTotal() {
        invoice.applyConfirmedAllocations(FinancialFixtures.usd("2000.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(invoice.getOutstandingAmount()).isEqualTo(FinancialFixtures.usd("3000.00"));
    }

    @Test
    void becomesPaidWhenAllocationsCoverTotal() {
        invoice.applyConfirmedAllocations(FinancialFixtures.usd("5000.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(invoice.getOutstandingAmount().isZero()).isTrue();
    }

    @Test
    void revertsToOpenWhenAllocationsAreWithdrawn() {
        invoice.applyConfirmedAllocations(FinancialFixtures.usd("5000.00"));
        invoice.applyConfirmedAllocations(FinancialFixtures.usd("0.00"));

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.OPEN);
    }

    @Test
    void rejectsAllocationsExceedingTotal() {
        assertThatThrownBy(() -> invoice.applyConfirmedAllocations(FinancialFixtures.usd("5000.01")))
                .isInstanceOf(InvariantViolationException.class);
    }

    @Test
    void reportsOverdueOnlyWhileSettleable() {
        assertThat(invoice.isOverdueOn(ISSUE_DATE.plusDays(45))).isTrue();

        invoice.applyConfirmedAllocations(FinancialFixtures.usd("5000.00"));

        assertThat(invoice.isOverdueOn(ISSUE_DATE.plusDays(45))).isFalse();
    }
}
