package com.ledgerflow.core.operations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByOperationIdOrderByRecordedAtAsc(UUID operationId);

    long countByOperationId(UUID operationId);
}
