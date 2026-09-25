package com.ledgerflow.core.invoice;

import com.ledgerflow.core.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_lines")
public class InvoiceLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    protected InvoiceLine() {
    }

    public InvoiceLine(UUID id, Invoice invoice, int lineNumber, String description, BigDecimal quantity,
                       Money unitPrice) {
        this.id = id;
        this.invoice = invoice;
        this.lineNumber = lineNumber;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = unitPrice.amount();
        this.lineTotal = unitPrice.multipliedBy(quantity).amount();
    }

    public UUID getId() {
        return id;
    }

    public Invoice getInvoice() {
        return invoice;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Money getUnitPrice() {
        return Money.of(unitPrice, invoice.getCurrency());
    }

    public Money getLineTotal() {
        return Money.of(lineTotal, invoice.getCurrency());
    }
}
