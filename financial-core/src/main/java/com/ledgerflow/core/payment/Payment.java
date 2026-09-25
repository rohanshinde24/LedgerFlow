package com.ledgerflow.core.payment;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.InvariantViolationException;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.party.Customer;
import com.ledgerflow.core.transaction.Transaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id")
    private Transaction transaction;

    @Column(name = "received_date", nullable = false)
    private LocalDate receivedDate;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod method;

    @Column(nullable = false)
    private String reference;

    @Column(name = "payer_name_raw", nullable = false)
    private String payerNameRaw;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    protected Payment() {
    }

    public Payment(UUID id, Business business, Customer customer, Transaction transaction, LocalDate receivedDate,
                   Money amount, PaymentMethod method, String reference, String payerNameRaw) {
        this.id = id;
        this.business = business;
        this.customer = customer;
        this.transaction = transaction;
        this.receivedDate = receivedDate;
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.method = method;
        this.reference = reference;
        this.payerNameRaw = payerNameRaw;
        this.status = PaymentStatus.UNAPPLIED;
    }

    /**
     * Invariant 3: a payment can never be applied to more than the money actually received.
     */
    public void applyConfirmedAllocations(Money confirmedTotal) {
        if (confirmedTotal.isNegative()) {
            throw new InvariantViolationException("Confirmed allocations cannot be negative for payment " + reference);
        }
        if (confirmedTotal.isGreaterThan(getAmount())) {
            throw new InvariantViolationException(
                    "Allocations %s exceed payment amount %s for payment %s".formatted(confirmedTotal, getAmount(), reference));
        }
        if (confirmedTotal.isZero()) {
            this.status = PaymentStatus.UNAPPLIED;
        } else if (confirmedTotal.compareTo(getAmount()) == 0) {
            this.status = PaymentStatus.APPLIED;
        } else {
            this.status = PaymentStatus.PARTIALLY_APPLIED;
        }
    }

    public UUID getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public LocalDate getReceivedDate() {
        return receivedDate;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public Currency getCurrency() {
        return currency;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public String getReference() {
        return reference;
    }

    public String getPayerNameRaw() {
        return payerNameRaw;
    }

    public PaymentStatus getStatus() {
        return status;
    }
}
