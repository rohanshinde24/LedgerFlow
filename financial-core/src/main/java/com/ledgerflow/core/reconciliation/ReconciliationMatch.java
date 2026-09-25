package com.ledgerflow.core.reconciliation;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.InvariantViolationException;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.payment.Payment;
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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_matches")
public class ReconciliationMatch {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "amount_applied", nullable = false)
    private BigDecimal amountApplied;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MatchMethod method;

    private BigDecimal confidence;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected ReconciliationMatch() {
    }

    public ReconciliationMatch(UUID id, Payment payment, Invoice invoice, Money amountApplied, MatchStatus status,
                               MatchMethod method, BigDecimal confidence) {
        if (!payment.getBusiness().getId().equals(invoice.getBusiness().getId())) {
            throw new InvariantViolationException("Payment and invoice belong to different businesses");
        }
        if (!amountApplied.isPositive()) {
            throw new InvariantViolationException("Applied amount must be positive");
        }
        if (amountApplied.isGreaterThan(payment.getAmount())) {
            throw new InvariantViolationException(
                    "Applied amount %s exceeds payment amount %s".formatted(amountApplied, payment.getAmount()));
        }
        if (amountApplied.isGreaterThan(invoice.getTotalAmount())) {
            throw new InvariantViolationException(
                    "Applied amount %s exceeds invoice total %s".formatted(amountApplied, invoice.getTotalAmount()));
        }
        this.id = id;
        this.business = payment.getBusiness();
        this.payment = payment;
        this.invoice = invoice;
        this.amountApplied = amountApplied.amount();
        this.status = status;
        this.method = method;
        this.confidence = confidence;
        this.confirmedAt = status == MatchStatus.CONFIRMED ? Instant.now() : null;
    }

    public void confirm() {
        this.status = MatchStatus.CONFIRMED;
        this.confirmedAt = Instant.now();
    }

    public void reject() {
        this.status = MatchStatus.REJECTED;
        this.confirmedAt = null;
    }

    public UUID getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public Payment getPayment() {
        return payment;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public Money getAmountApplied() {
        return Money.of(amountApplied, invoice.getCurrency());
    }

    public MatchStatus getStatus() {
        return status;
    }

    public MatchMethod getMethod() {
        return method;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
