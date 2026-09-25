package com.ledgerflow.core.payment;

import com.ledgerflow.core.common.MoneyView;

import java.time.LocalDate;
import java.util.UUID;

public record PaymentResponse(UUID id,
                              UUID businessId,
                              LocalDate receivedDate,
                              MoneyView amount,
                              PaymentMethod method,
                              String reference,
                              String payerName,
                              UUID customerId,
                              String customerName,
                              UUID transactionId,
                              PaymentStatus status) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getBusiness().getId(),
                payment.getReceivedDate(),
                MoneyView.of(payment.getAmount()),
                payment.getMethod(),
                payment.getReference(),
                payment.getPayerNameRaw(),
                payment.getCustomer() == null ? null : payment.getCustomer().getId(),
                payment.getCustomer() == null ? null : payment.getCustomer().getName(),
                payment.getTransaction() == null ? null : payment.getTransaction().getId(),
                payment.getStatus());
    }
}
