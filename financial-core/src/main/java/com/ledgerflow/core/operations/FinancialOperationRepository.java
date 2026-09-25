package com.ledgerflow.core.operations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FinancialOperationRepository extends JpaRepository<FinancialOperation, UUID> {

    Optional<FinancialOperation> findByOperationKey(String operationKey);
}
