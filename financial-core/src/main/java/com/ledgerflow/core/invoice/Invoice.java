package com.ledgerflow.core.invoice;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.InvariantViolationException;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.party.Customer;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "invoice_number", nullable = false)
    private String invoiceNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false)
    private BigDecimal subtotal;

    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status;

    private String memo;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber asc")
    private List<InvoiceLine> lines = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(UUID id, Business business, Customer customer, String invoiceNumber, LocalDate issueDate,
                   LocalDate dueDate, Money subtotal, Money taxAmount, InvoiceStatus status, String memo) {
        this.id = id;
        this.business = business;
        this.customer = customer;
        this.invoiceNumber = invoiceNumber;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
        this.currency = subtotal.currency();
        this.subtotal = subtotal.amount();
        this.taxAmount = taxAmount.amount();
        this.totalAmount = subtotal.plus(taxAmount).amount();
        this.amountPaid = Money.zero(currency).amount();
        this.status = status;
        this.memo = memo;
    }

    public void addLine(InvoiceLine line) {
        lines.add(line);
    }

    /**
     * Invariant 2/4: paid amount is always the sum of confirmed allocations and never exceeds the total.
     */
    public void applyConfirmedAllocations(Money confirmedTotal) {
        if (confirmedTotal.isNegative()) {
            throw new InvariantViolationException("Confirmed allocations cannot be negative for invoice " + invoiceNumber);
        }
        if (confirmedTotal.isGreaterThan(getTotalAmount())) {
            throw new InvariantViolationException(
                    "Allocations %s exceed total %s for invoice %s".formatted(confirmedTotal, getTotalAmount(), invoiceNumber));
        }
        this.amountPaid = confirmedTotal.amount();
        this.status = deriveStatus(confirmedTotal);
    }

    private InvoiceStatus deriveStatus(Money paid) {
        if (status == InvoiceStatus.VOID) {
            return InvoiceStatus.VOID;
        }
        if (paid.isZero()) {
            return status == InvoiceStatus.DRAFT ? InvoiceStatus.DRAFT : InvoiceStatus.OPEN;
        }
        return paid.compareTo(getTotalAmount()) == 0 ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID;
    }

    public Money getOutstandingAmount() {
        return getTotalAmount().minus(getAmountPaid());
    }

    public boolean isOverdueOn(LocalDate date) {
        return status.isSettleable() && dueDate.isBefore(date);
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

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public LocalDate getIssueDate() {
        return issueDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Money getSubtotal() {
        return Money.of(subtotal, currency);
    }

    public Money getTaxAmount() {
        return Money.of(taxAmount, currency);
    }

    public Money getTotalAmount() {
        return Money.of(totalAmount, currency);
    }

    public Money getAmountPaid() {
        return Money.of(amountPaid, currency);
    }

    public InvoiceStatus getStatus() {
        return status;
    }

    public String getMemo() {
        return memo;
    }

    public List<InvoiceLine> getLines() {
        return List.copyOf(lines);
    }
}
