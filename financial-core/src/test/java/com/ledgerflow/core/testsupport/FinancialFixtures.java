package com.ledgerflow.core.testsupport;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.invoice.InvoiceStatus;
import com.ledgerflow.core.party.Customer;
import com.ledgerflow.core.payment.Payment;
import com.ledgerflow.core.payment.PaymentMethod;

import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

public final class FinancialFixtures {

    public static final Currency USD = Currency.getInstance("USD");

    private FinancialFixtures() {
    }

    public static Business business() {
        return new Business(UUID.randomUUID(), "Northwind Studio", "Northwind Studio LLC", USD, (short) 1);
    }

    public static Customer customer(Business business, String name) {
        return new Customer(UUID.randomUUID(), business, name, "ap@example.com");
    }

    public static Invoice invoice(Business business, Customer customer, String invoiceNumber, String subtotal,
                                  LocalDate issueDate) {
        return new Invoice(UUID.randomUUID(), business, customer, invoiceNumber, issueDate, issueDate.plusDays(30),
                Money.of(subtotal, USD), Money.zero(USD), InvoiceStatus.OPEN, null);
    }

    public static Payment payment(Business business, Customer customer, String amount, String reference,
                                  String payerName, LocalDate receivedDate) {
        return new Payment(UUID.randomUUID(), business, customer, null, receivedDate, Money.of(amount, USD),
                PaymentMethod.ACH, reference, payerName);
    }

    public static Money usd(String amount) {
        return Money.of(amount, USD);
    }
}
