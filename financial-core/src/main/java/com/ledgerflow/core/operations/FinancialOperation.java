package com.ledgerflow.core.operations;

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

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to change financial state, identified by a caller-supplied key.
 *
 * <p>The key is what makes a retry safe. A caller that never learns the outcome of its request —
 * because the response was lost, or the process died after committing — replays the same key and
 * receives the original result rather than causing a second mutation.
 */
@Entity
@Table(name = "financial_operations")
public class FinancialOperation {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "operation_key", nullable = false, updatable = false)
    private String operationKey;

    @Column(name = "operation_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private OperationType operationType;

    @Column(name = "subject_type", nullable = false)
    private String subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private OperationStatus status;

    @Column
    private String result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected FinancialOperation() {
    }

    public FinancialOperation(UUID id, Business business, String operationKey, OperationType operationType,
                              String subjectType, UUID subjectId) {
        this.id = id;
        this.business = business;
        this.operationKey = operationKey;
        this.operationType = operationType;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.status = OperationStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void complete(String result) {
        this.status = OperationStatus.COMPLETED;
        this.result = result;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Business getBusiness() {
        return business;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public OperationType getOperationType() {
        return operationType;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public OperationStatus getStatus() {
        return status;
    }

    public String getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
