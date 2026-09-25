package com.ledgerflow.core.operations;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class FinancialOperationService {

    private final FinancialOperationRepository operations;
    private final OperationWriter writer;

    public FinancialOperationService(FinancialOperationRepository operations, OperationWriter writer) {
        this.operations = operations;
        this.writer = writer;
    }

    /**
     * Apply a categorization, or return the result of the one already applied under this key.
     *
     * <p>Two callers racing on the same key both find nothing, both attempt the write, and exactly
     * one wins the unique constraint. The loser does not retry the mutation: it reads what the
     * winner committed and reports that. A duplicate request is therefore indistinguishable from
     * the original, which is the property a lost response depends on.
     */
    public OperationOutcome categorizeTransaction(String operationKey, UUID businessId,
                                                  UUID transactionId, String chartOfAccountCode) {
        Optional<FinancialOperation> existing = findByKey(operationKey);
        if (existing.isPresent()) {
            return OperationOutcome.replayed(existing.get());
        }
        try {
            return OperationOutcome.applied(
                    writer.categorize(operationKey, businessId, transactionId, chartOfAccountCode));
        } catch (DataIntegrityViolationException race) {
            return OperationOutcome.replayed(findByKey(operationKey).orElseThrow(
                    () -> new IllegalStateException("operation vanished after key conflict: " + operationKey)));
        }
    }

    @Transactional(readOnly = true)
    public Optional<FinancialOperation> findByKey(String operationKey) {
        return operations.findByOperationKey(operationKey);
    }

    public record OperationOutcome(UUID operationId, String chartOfAccountCode, OperationStatus status,
                                   boolean replayed) {

        static OperationOutcome applied(FinancialOperation operation) {
            return new OperationOutcome(operation.getId(), operation.getResult(), operation.getStatus(), false);
        }

        static OperationOutcome replayed(FinancialOperation operation) {
            return new OperationOutcome(operation.getId(), operation.getResult(), operation.getStatus(), true);
        }
    }
}
