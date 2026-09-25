package com.ledgerflow.core.ledger;

import com.ledgerflow.core.business.Business;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "chart_of_accounts")
public class ChartOfAccountEntry {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountCategory category;

    protected ChartOfAccountEntry() {
    }

    public ChartOfAccountEntry(UUID id, Business business, String code, String name, AccountCategory category) {
        this.id = id;
        this.business = business;
        this.code = code;
        this.name = name;
        this.category = category;
    }

    public UUID getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AccountCategory getCategory() {
        return category;
    }
}
