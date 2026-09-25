package com.ledgerflow.core.payment;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Query("""
            select p from Payment p
            left join fetch p.customer
            where p.business.id = :businessId
              and (:status is null or p.status = :status)
            """)
    Page<Payment> search(UUID businessId, PaymentStatus status, Pageable pageable);

    @Query("""
            select p from Payment p
            left join fetch p.customer
            left join fetch p.transaction
            where p.id = :id
            """)
    Optional<Payment> findDetailById(UUID id);
}
