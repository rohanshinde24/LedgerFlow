package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.party.Customer;
import com.ledgerflow.core.testsupport.FinancialFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.ledgerflow.core.testsupport.FinancialFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;

class InvoiceCombinationGeneratorTest {

    private static final LocalDate ISSUE_DATE = LocalDate.of(2025, 3, 1);

    private final InvoiceCombinationGenerator generator = new InvoiceCombinationGenerator();

    private Business business;
    private Customer customer;

    @BeforeEach
    void setUp() {
        business = FinancialFixtures.business();
        customer = FinancialFixtures.customer(business, "Halden Robotics");
    }

    @Test
    void findsTheTwoInvoicesThatTogetherSettleThePayment() {
        List<Invoice> invoices = List.of(
                invoice("INV-1001", "1200.00", 0),
                invoice("INV-1002", "1875.58", 1),
                invoice("INV-1003", "4325.20", 2));

        List<InvoiceCombination> combinations = generator.settlingExactly(invoices, usd("3075.58"));

        assertThat(combinations).hasSize(1);
        assertThat(numbersOf(combinations.get(0))).containsExactly("INV-1001", "INV-1002");
        assertThat(combinations.get(0).total()).isEqualTo(usd("3075.58"));
    }

    @Test
    void reportsEveryDistinctCombinationWhenSeveralSumToTheSameTotal() {
        List<Invoice> invoices = List.of(
                invoice("INV-2001", "500.00", 0),
                invoice("INV-2002", "500.00", 1),
                invoice("INV-2003", "1000.00", 2),
                invoice("INV-2004", "250.00", 3),
                invoice("INV-2005", "750.00", 4));

        List<InvoiceCombination> combinations = generator.settlingExactly(invoices, usd("1000.00"));

        assertThat(combinations).hasSizeGreaterThan(1);
        assertThat(combinations).allSatisfy(combination ->
                assertThat(combination.total()).isEqualTo(usd("1000.00")));
        assertThat(combinations).extracting(this::numbersOf)
                .contains(List.of("INV-2001", "INV-2002"), List.of("INV-2004", "INV-2005"));
    }

    @Test
    void neverReportsASingleInvoiceAsACombination() {
        List<Invoice> invoices = List.of(
                invoice("INV-3001", "900.00", 0),
                invoice("INV-3002", "300.00", 1));

        assertThat(generator.settlingExactly(invoices, usd("900.00"))).isEmpty();
    }

    @Test
    void ignoresInvoicesLargerThanThePayment() {
        List<Invoice> invoices = List.of(
                invoice("INV-4001", "400.00", 0),
                invoice("INV-4002", "600.00", 1),
                invoice("INV-4003", "9999.00", 2));

        List<InvoiceCombination> combinations = generator.settlingExactly(invoices, usd("1000.00"));

        assertThat(combinations).hasSize(1);
        assertThat(numbersOf(combinations.get(0))).containsExactly("INV-4001", "INV-4002");
    }

    @Test
    void findsNothingWhenNoSubsetSumsExactly() {
        List<Invoice> invoices = List.of(
                invoice("INV-5001", "333.33", 0),
                invoice("INV-5002", "412.10", 1));

        assertThat(generator.settlingExactly(invoices, usd("1000.00"))).isEmpty();
    }

    @Test
    void excludesInvoicesAlreadySettledByEarlierPayments() {
        Invoice paid = invoice("INV-6001", "400.00", 0);
        paid.applyConfirmedAllocations(usd("400.00"));

        List<Invoice> invoices = List.of(paid, invoice("INV-6002", "600.00", 1), invoice("INV-6003", "400.00", 2));

        List<InvoiceCombination> combinations = generator.settlingExactly(invoices, usd("1000.00"));

        assertThat(combinations).hasSize(1);
        assertThat(numbersOf(combinations.get(0))).containsExactly("INV-6002", "INV-6003");
    }

    @Test
    void reportsNothingRatherThanTruncatingAnOversizedInvoiceSet() {
        List<Invoice> invoices = new ArrayList<>();
        for (int index = 0; index <= InvoiceCombinationGenerator.MAX_INVOICES_ENUMERATED; index++) {
            invoices.add(invoice("INV-70%02d".formatted(index), "500.00", index));
        }

        assertThat(generator.settlingExactly(invoices, usd("1000.00"))).isEmpty();
    }

    @Test
    void treatsANonPositivePaymentAsHavingNothingToSettle() {
        List<Invoice> invoices = List.of(invoice("INV-8001", "500.00", 0));

        assertThat(generator.settlingExactly(invoices, usd("0.00"))).isEmpty();
    }

    private Invoice invoice(String number, String amount, int dueDateOffset) {
        return FinancialFixtures.invoice(business, customer, number, amount, ISSUE_DATE.plusDays(dueDateOffset));
    }

    private List<String> numbersOf(InvoiceCombination combination) {
        return combination.invoices().stream().map(Invoice::getInvoiceNumber).toList();
    }
}
