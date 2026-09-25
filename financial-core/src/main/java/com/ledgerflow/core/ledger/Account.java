package com.ledgerflow.core.ledger;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.common.Money;
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
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private AccountType accountType;

    @Column(nullable = false)
    private String institution;

    @Column(name = "account_number_mask", nullable = false)
    private String accountNumberMask;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "opening_balance", nullable = false)
    private BigDecimal openingBalanceAmount;

    protected Account() {
    }

    public Account(UUID id, Business business, String name, AccountType accountType, String institution,
                   String accountNumberMask, Money openingBalance) {
        this.id = id;
        this.business = business;
        this.name = name;
        this.accountType = accountType;
        this.institution = institution;
        this.accountNumberMask = accountNumberMask;
        this.currency = openingBalance.currency();
        this.openingBalanceAmount = openingBalance.amount();
    }

    public UUID getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public String getName() {
        return name;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public String getInstitution() {
        return institution;
    }

    public String getAccountNumberMask() {
        return accountNumberMask;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Money getOpeningBalance() {
        return Money.of(openingBalanceAmount, currency);
    }
}
