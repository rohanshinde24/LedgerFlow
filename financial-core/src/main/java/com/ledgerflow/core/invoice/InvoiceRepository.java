package com.ledgerflow.core.invoice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    @Query("""
            select i from Invoice i
            join fetch i.customer
            where i.business.id = :businessId
              and (:customerId is null or i.customer.id = :customerId)
              and (:status is null or i.status = :status)
            """)
    Page<Invoice> search(UUID businessId, UUID customerId, InvoiceStatus status, Pageable pageable);

    @Query("""
            select i from Invoice i
            join fetch i.customer
            left join fetch i.lines
            where i.id = :id
            """)
    Optional<Invoice> findDetailById(UUID id);

    @Query("""
            select i from Invoice i
            join fetch i.customer
            where i.business.id = :businessId
              and i.status in (com.ledgerflow.core.invoice.InvoiceStatus.OPEN,
                               com.ledgerflow.core.invoice.InvoiceStatus.PARTIALLY_PAID)
              and i.issueDate <= :issuedOnOrBefore
              and i.issueDate >= :issuedOnOrAfter
            """)
    List<Invoice> findSettleableIssuedBetween(UUID businessId, LocalDate issuedOnOrAfter, LocalDate issuedOnOrBefore);

    List<Invoice> findByBusinessIdAndStatusIn(UUID businessId, List<InvoiceStatus> statuses);
}
