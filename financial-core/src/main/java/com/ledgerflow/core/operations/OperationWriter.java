package com.ledgerflow.core.operations;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.business.BusinessRepository;
import com.ledgerflow.core.common.InvariantViolationException;
import com.ledgerflow.core.common.ResourceNotFoundException;
import com.ledgerflow.core.ledger.ChartOfAccountEntry;
import com.ledgerflow.core.ledger.ChartOfAccountRepository;
import com.ledgerflow.core.transaction.CategorizationSource;
import com.ledgerflow.core.transaction.CategorizationStatus;
import com.ledgerflow.core.transaction.Transaction;
import com.ledgerflow.core.transaction.TransactionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only path in the system that changes financial state.
 *
 * <p>Claiming the operation key, applying the change, and recording the audit event all happen in
 * one transaction. That is what makes a crash safe: there is no window in which the key is claimed
 * but the change did not happen, so a replay can never observe a half-finished operation.
 */
@Component
public class OperationWriter {

    private final FinancialOperationRepository operations;
    private final AuditEventRepository auditEvents;
    private final TransactionRepository transactions;
    private final ChartOfAccountRepository chartOfAccounts;
    private final BusinessRepository businesses;

    public OperationWriter(FinancialOperationRepository operations, AuditEventRepository auditEvents,
                           TransactionRepository transactions, ChartOfAccountRepository chartOfAccounts,
                           BusinessRepository businesses) {
        this.operations = operations;
        this.auditEvents = auditEvents;
        this.transactions = transactions;
        this.chartOfAccounts = chartOfAccounts;
        this.businesses = businesses;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FinancialOperation categorize(String operationKey, UUID businessId, UUID transactionId,
                                         String chartOfAccountCode) {
        Business business = businesses.findById(businessId)
                .orElseThrow(() -> new ResourceNotFoundException("business", businessId));
        Transaction transaction = transactions.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("transaction", transactionId));
        ChartOfAccountEntry entry = chartOfAccounts.findByBusinessIdAndCode(businessId, chartOfAccountCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown chart of account code: " + chartOfAccountCode));

        // The key is claimed before anything else is examined, so that concurrent callers are
        // separated by the unique constraint rather than by whatever they each managed to read
        // first. A retry that loses this race is a duplicate request and must replay; checking the
        // subject's state ahead of the claim would instead report it as a conflicting mutation.
        FinancialOperation operation = operations.saveAndFlush(new FinancialOperation(
                UUID.randomUUID(), business, operationKey, OperationType.CATEGORIZE_TRANSACTION,
                "TRANSACTION", transactionId));

        if (transaction.getCategorizationStatus() == CategorizationStatus.CATEGORIZED) {
            // A different key reaching a settled transaction is a second mutation of the same
            // subject, not a retry. Rolling back here releases the key it just claimed.
            throw new InvariantViolationException(
                    "transaction is already categorized: " + transactionId);
        }

        transaction.categorize(entry, null, CategorizationSource.AGENT);
        operation.complete(chartOfAccountCode);

        auditEvents.save(new AuditEvent(UUID.randomUUID(), business, operation.getId(),
                "TRANSACTION_CATEGORIZED",
                "transaction=" + transactionId + " code=" + chartOfAccountCode));
        return operation;
    }
}
