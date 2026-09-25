package com.ledgerflow.core.invoice;

import com.ledgerflow.core.common.MoneyView;

import java.time.LocalDate;
import java.util.UUID;

public record InvoiceResponse(UUID id,
                              String invoiceNumber,
                              UUID customerId,
                              String customerName,
                              LocalDate issueDate,
                              LocalDate dueDate,
                              MoneyView subtotal,
                              MoneyView taxAmount,
                              MoneyView totalAmount,
                              MoneyView amountPaid,
                              MoneyView outstandingAmount,
                              InvoiceStatus status,
                              boolean overdue,
                              String memo) {

    public static InvoiceResponse from(Invoice invoice, LocalDate asOf) {
        return new InvoiceResponse(
                invoice.getId(),
                invoice.getInvoiceNumber(),
                invoice.getCustomer().getId(),
                invoice.getCustomer().getName(),
                invoice.getIssueDate(),
                invoice.getDueDate(),
                MoneyView.of(invoice.getSubtotal()),
                MoneyView.of(invoice.getTaxAmount()),
                MoneyView.of(invoice.getTotalAmount()),
                MoneyView.of(invoice.getAmountPaid()),
                MoneyView.of(invoice.getOutstandingAmount()),
                invoice.getStatus(),
                invoice.isOverdueOn(asOf),
                invoice.getMemo());
    }
}
