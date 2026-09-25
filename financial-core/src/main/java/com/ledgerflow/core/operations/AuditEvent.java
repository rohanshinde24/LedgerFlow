package com.ledgerflow.core.operations;

import com.ledgerflow.core.business.Business;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable record that something happened to financial state.
 *
 * <p>There is no setter and no update path. The database refuses updates and deletes outright, so
 * the trail cannot be rewritten by a bug in this layer.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "operation_id", nullable = false, updatable = false)
    private UUID operationId;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private String detail;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected AuditEvent() {
    }

    public AuditEvent(UUID id, Business business, UUID operationId, String eventType, String detail) {
        this.id = id;
        this.business = business;
        this.operationId = operationId;
        this.eventType = eventType;
        this.detail = detail;
        this.recordedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
