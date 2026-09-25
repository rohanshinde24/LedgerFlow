package com.ledgerflow.core.business;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Entity
@Table(name = "businesses")
public class Business {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "legal_name", nullable = false)
    private String legalName;

    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "fiscal_year_start_month", nullable = false)
    private short fiscalYearStartMonth;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected Business() {
    }

    public Business(UUID id, String name, String legalName, Currency currency, short fiscalYearStartMonth) {
        this.id = id;
        this.name = name;
        this.legalName = legalName;
        this.currency = currency;
        this.fiscalYearStartMonth = fiscalYearStartMonth;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLegalName() {
        return legalName;
    }

    public Currency getCurrency() {
        return currency;
    }

    public short getFiscalYearStartMonth() {
        return fiscalYearStartMonth;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
