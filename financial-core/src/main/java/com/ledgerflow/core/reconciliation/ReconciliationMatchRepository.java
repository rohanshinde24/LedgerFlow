package com.ledgerflow.core.reconciliation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ReconciliationMatchRepository extends JpaRepository<ReconciliationMatch, UUID> {

    List<ReconciliationMatch> findByPaymentId(UUID paymentId);

    List<ReconciliationMatch> findByInvoiceId(UUID invoiceId);

    List<ReconciliationMatch> findByPaymentIdAndStatus(UUID paymentId, MatchStatus status);

    List<ReconciliationMatch> findByInvoiceIdAndStatus(UUID invoiceId, MatchStatus status);

    @Query("""
            select m from ReconciliationMatch m
            join fetch m.invoice i
            join fetch i.customer
            where m.payment.id = :paymentId
            """)
    List<ReconciliationMatch> findWithInvoiceByPaymentId(UUID paymentId);
}
