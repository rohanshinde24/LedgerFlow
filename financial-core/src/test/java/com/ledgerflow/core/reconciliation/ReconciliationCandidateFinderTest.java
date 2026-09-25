package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.invoice.InvoiceRepository;
import com.ledgerflow.core.party.Customer;
import com.ledgerflow.core.payment.Payment;
import com.ledgerflow.core.testsupport.FinancialFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationCandidateFinderTest {

    private static final LocalDate ISSUE_DATE = LocalDate.of(2025, 3, 1);
    private static final LocalDate RECEIVED_DATE = ISSUE_DATE.plusDays(25);

    private final Business business = FinancialFixtures.business();
    private final Customer customer = FinancialFixtures.customer(business, "Calder Manufacturing");
    private final InvoiceRepository invoiceRepository = mock(InvoiceRepository.class);
    private final ReconciliationCandidateFinder finder =
            new ReconciliationCandidateFinder(invoiceRepository, new InvoiceCombinationGenerator());

    @Test
    void prefersTheInvoiceNamedInThePaymentReferenceOverIdenticalAmountDecoys() {
        Invoice referenced = FinancialFixtures.invoice(business, customer, "INV-2025-041", "4200.00", ISSUE_DATE);
        Invoice decoy = FinancialFixtures.invoice(business, customer, "INV-2025-042", "4200.00", ISSUE_DATE);
        givenSettleable(referenced, decoy);

        Payment payment = FinancialFixtures.payment(business, customer, "4200.00", "ACH CREDIT INV-2025-041",
                "Calder Manufacturing", RECEIVED_DATE);

        ReconciliationCandidateSet result = finder.findCandidates(payment, payment.getAmount());

        assertThat(result.topCandidate().invoice().getInvoiceNumber()).isEqualTo("INV-2025-041");
        assertThat(result.topCandidate().signals()).contains("REFERENCE_CONTAINS_INVOICE_NUMBER");
        assertThat(result.ambiguous()).isFalse();
    }

    @Test
    void flagsAmbiguityWhenIdenticalInvoicesAreIndistinguishable() {
        Invoice first = FinancialFixtures.invoice(business, customer, "INV-2025-051", "4200.00", ISSUE_DATE);
        Invoice second = FinancialFixtures.invoice(business, customer, "INV-2025-052", "4200.00", ISSUE_DATE);
        givenSettleable(first, second);

        Payment payment = FinancialFixtures.payment(business, customer, "4200.00", "ACH CREDIT",
                "Calder Manufacturing", RECEIVED_DATE);

        ReconciliationCandidateSet result = finder.findCandidates(payment, payment.getAmount());

        assertThat(result.candidates()).hasSize(2);
        assertThat(result.ambiguous()).isTrue();
    }

    @Test
    void suggestsOnlyTheOutstandingAmountWhenThePaymentExceedsTheInvoice() {
        Invoice invoice = FinancialFixtures.invoice(business, customer, "INV-2025-061", "1000.00", ISSUE_DATE);
        givenSettleable(invoice);

        Payment payment = FinancialFixtures.payment(business, customer, "2500.00", "ACH CREDIT INV-2025-061",
                "Calder Manufacturing", RECEIVED_DATE);

        ReconciliationCandidateSet result = finder.findCandidates(payment, payment.getAmount());

        assertThat(result.topCandidate().suggestedAmount()).isEqualTo(FinancialFixtures.usd("1000.00"));
    }

    @Test
    void excludesFullySettledInvoicesFromTheConsideredSet() {
        Invoice settled = FinancialFixtures.invoice(business, customer, "INV-2025-071", "4200.00", ISSUE_DATE);
        settled.applyConfirmedAllocations(FinancialFixtures.usd("4200.00"));
        givenSettleable(settled);

        Payment payment = FinancialFixtures.payment(business, customer, "4200.00", "ACH CREDIT INV-2025-071",
                "Calder Manufacturing", RECEIVED_DATE);

        ReconciliationCandidateSet result = finder.findCandidates(payment, payment.getAmount());

        assertThat(result.consideredInvoiceCount()).isZero();
        assertThat(result.candidates()).isEmpty();
        assertThat(result.ambiguous()).isTrue();
    }

    @Test
    void narrowsTheConsideredSetToTheInvoicesOfAKnownCustomer() {
        Customer otherCustomer = FinancialFixtures.customer(business, "Rivera Logistics");
        Invoice ownedByPayer = FinancialFixtures.invoice(business, customer, "INV-2025-081", "4200.00", ISSUE_DATE);
        Invoice ownedByOther = FinancialFixtures.invoice(business, otherCustomer, "INV-2025-082", "4200.00", ISSUE_DATE);
        givenSettleable(ownedByPayer, ownedByOther);

        Payment payment = FinancialFixtures.payment(business, customer, "4200.00", "ACH CREDIT",
                "Calder Manufacturing", RECEIVED_DATE);

        ReconciliationCandidateSet result = finder.findCandidates(payment, payment.getAmount());

        assertThat(result.consideredInvoiceCount()).isEqualTo(1);
        assertThat(result.topCandidate().invoice().getInvoiceNumber()).isEqualTo("INV-2025-081");
    }

    @Test
    void surfacesAMultiInvoiceSettlementAndTreatsItAsAmbiguous() {
        Invoice first = FinancialFixtures.invoice(business, customer, "INV-2025-091", "1200.00", ISSUE_DATE);
        Invoice second = FinancialFixtures.invoice(business, customer, "INV-2025-092", "3000.00", ISSUE_DATE);
        givenSettleable(first, second);

        Payment payment = FinancialFixtures.payment(business, customer, "4200.00", "ACH CREDIT",
                "Calder Manufacturing", RECEIVED_DATE);

        ReconciliationCandidateSet result = finder.findCandidates(payment, payment.getAmount());

        assertThat(result.exactCombinations()).hasSize(1);
        assertThat(result.exactCombinations().get(0).invoices())
                .extracting(Invoice::getInvoiceNumber)
                .containsExactlyInAnyOrder("INV-2025-091", "INV-2025-092");
        assertThat(result.ambiguous()).isTrue();
    }

    @Test
    void findsNoCombinationForAnOrdinarySingleInvoicePayment() {
        Invoice invoice = FinancialFixtures.invoice(business, customer, "INV-2025-101", "4200.00", ISSUE_DATE);
        givenSettleable(invoice);

        Payment payment = FinancialFixtures.payment(business, customer, "4200.00", "ACH CREDIT INV-2025-101",
                "Calder Manufacturing", RECEIVED_DATE);

        ReconciliationCandidateSet result = finder.findCandidates(payment, payment.getAmount());

        assertThat(result.exactCombinations()).isEmpty();
        assertThat(result.ambiguous()).isFalse();
    }

    private void givenSettleable(Invoice... invoices) {
        when(invoiceRepository.findSettleableIssuedBetween(eq(business.getId()), any(LocalDate.class),
                any(LocalDate.class))).thenReturn(List.of(invoices));
    }
}
