package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.InvariantViolationException;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.party.Customer;
import com.ledgerflow.core.payment.Payment;
import com.ledgerflow.core.testsupport.FinancialFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReconciliationMatchTest {

    private static final LocalDate ISSUE_DATE = LocalDate.of(2025, 3, 1);

    private final Business business = FinancialFixtures.business();
    private final Customer customer = FinancialFixtures.customer(business, "Calder Manufacturing");

    @Test
    void allowsPartialAllocationOfAPayment() {
        Invoice invoice = FinancialFixtures.invoice(business, customer, "INV-2025-010", "5000.00", ISSUE_DATE);
        Payment payment = FinancialFixtures.payment(business, customer, "8000.00", "ACH REF INV-2025-010",
                "Calder Manufacturing", ISSUE_DATE.plusDays(20));

        ReconciliationMatch match = match(payment, invoice, "5000.00");

        assertThat(match.getAmountApplied()).isEqualTo(FinancialFixtures.usd("5000.00"));
        assertThat(match.getStatus()).isEqualTo(MatchStatus.CONFIRMED);
    }

    @Test
    void rejectsAllocationLargerThanThePayment() {
        Invoice invoice = FinancialFixtures.invoice(business, customer, "INV-2025-011", "5000.00", ISSUE_DATE);
        Payment payment = FinancialFixtures.payment(business, customer, "1000.00", "ACH", "Calder",
                ISSUE_DATE.plusDays(20));

        assertThatThrownBy(() -> match(payment, invoice, "1000.01"))
                .isInstanceOf(InvariantViolationException.class);
    }

    @Test
    void rejectsAllocationLargerThanTheInvoice() {
        Invoice invoice = FinancialFixtures.invoice(business, customer, "INV-2025-012", "500.00", ISSUE_DATE);
        Payment payment = FinancialFixtures.payment(business, customer, "5000.00", "ACH", "Calder",
                ISSUE_DATE.plusDays(20));

        assertThatThrownBy(() -> match(payment, invoice, "600.00"))
                .isInstanceOf(InvariantViolationException.class);
    }

    @Test
    void rejectsMatchesAcrossBusinesses() {
        Business otherBusiness = FinancialFixtures.business();
        Invoice invoice = FinancialFixtures.invoice(otherBusiness,
                FinancialFixtures.customer(otherBusiness, "Other Customer"), "INV-2025-013", "500.00", ISSUE_DATE);
        Payment payment = FinancialFixtures.payment(business, customer, "500.00", "ACH", "Calder",
                ISSUE_DATE.plusDays(20));

        assertThatThrownBy(() -> match(payment, invoice, "500.00"))
                .isInstanceOf(InvariantViolationException.class);
    }

    @Test
    void rejectsNonPositiveAllocations() {
        Invoice invoice = FinancialFixtures.invoice(business, customer, "INV-2025-014", "500.00", ISSUE_DATE);
        Payment payment = FinancialFixtures.payment(business, customer, "500.00", "ACH", "Calder",
                ISSUE_DATE.plusDays(20));

        assertThatThrownBy(() -> match(payment, invoice, "0.00"))
                .isInstanceOf(InvariantViolationException.class);
    }

    private ReconciliationMatch match(Payment payment, Invoice invoice, String amount) {
        return new ReconciliationMatch(UUID.randomUUID(), payment, invoice, FinancialFixtures.usd(amount),
                MatchStatus.CONFIRMED, MatchMethod.EXACT, BigDecimal.ONE);
    }
}
