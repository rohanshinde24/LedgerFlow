package com.ledgerflow.core.transaction;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.ledger.Account;
import com.ledgerflow.core.ledger.ChartOfAccountEntry;
import com.ledgerflow.core.party.Vendor;
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
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "booked_date", nullable = false)
    private LocalDate bookedDate;

    @Column(nullable = false)
    private String description;

    @Column(name = "counterparty_raw", nullable = false)
    private String counterpartyRaw;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "coa_entry_id")
    private ChartOfAccountEntry coaEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @Enumerated(EnumType.STRING)
    @Column(name = "categorization_status", nullable = false)
    private CategorizationStatus categorizationStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "categorization_source")
    private CategorizationSource categorizationSource;

    @Column(name = "external_ref", nullable = false)
    private String externalRef;

    protected Transaction() {
    }

    public Transaction(UUID id, Business business, Account account, LocalDate bookedDate, String description,
                       String counterpartyRaw, Money amount, String externalRef) {
        this.id = id;
        this.business = business;
        this.account = account;
        this.bookedDate = bookedDate;
        this.description = description;
        this.counterpartyRaw = counterpartyRaw;
        this.amount = amount.amount();
        this.currency = amount.currency();
        this.externalRef = externalRef;
        this.categorizationStatus = CategorizationStatus.UNCATEGORIZED;
    }

    public void categorize(ChartOfAccountEntry entry, Vendor matchedVendor, CategorizationSource source) {
        this.coaEntry = entry;
        this.vendor = matchedVendor;
        this.categorizationSource = source;
        this.categorizationStatus = CategorizationStatus.CATEGORIZED;
    }

    public void flagForReview(Vendor matchedVendor) {
        this.vendor = matchedVendor;
        this.categorizationStatus = CategorizationStatus.NEEDS_REVIEW;
    }

    public boolean isMoneyOut() {
        return amount.signum() < 0;
    }

    public UUID getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public Account getAccount() {
        return account;
    }

    public LocalDate getBookedDate() {
        return bookedDate;
    }

    public String getDescription() {
        return description;
    }

    public String getCounterpartyRaw() {
        return counterpartyRaw;
    }

    public Money getAmount() {
        return Money.of(amount, currency);
    }

    public Currency getCurrency() {
        return currency;
    }

    public ChartOfAccountEntry getCoaEntry() {
        return coaEntry;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public CategorizationStatus getCategorizationStatus() {
        return categorizationStatus;
    }

    public CategorizationSource getCategorizationSource() {
        return categorizationSource;
    }

    public String getExternalRef() {
        return externalRef;
    }
}
