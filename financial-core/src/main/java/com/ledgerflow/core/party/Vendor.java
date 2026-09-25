package com.ledgerflow.core.party;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.ledger.ChartOfAccountEntry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "vendors")
public class Vendor {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "default_coa_entry_id")
    private ChartOfAccountEntry defaultCoaEntry;

    protected Vendor() {
    }

    public Vendor(UUID id, Business business, String name, ChartOfAccountEntry defaultCoaEntry) {
        this.id = id;
        this.business = business;
        this.name = name;
        this.defaultCoaEntry = defaultCoaEntry;
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

    public ChartOfAccountEntry getDefaultCoaEntry() {
        return defaultCoaEntry;
    }
}
