package com.ledgerflow.core.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    @Override
    @EntityGraph(attributePaths = {"account", "coaEntry", "vendor"})
    Page<Transaction> findAll(Specification<Transaction> specification, Pageable pageable);

    @Override
    @EntityGraph(attributePaths = {"account", "coaEntry", "vendor"})
    Optional<Transaction> findById(UUID id);

    @Query("""
            select t from Transaction t
            join fetch t.account
            left join fetch t.coaEntry
            left join fetch t.vendor
            where t.business.id = :businessId
              and t.bookedDate between :from and :to
            """)
    List<Transaction> findForPeriod(UUID businessId, LocalDate from, LocalDate to);

    @Query("""
            select new com.ledgerflow.core.transaction.CounterpartyCategorization(
                c.code, c.name, count(t), max(t.bookedDate))
            from Transaction t
            join t.coaEntry c
            where t.business.id = :businessId
              and upper(t.counterpartyRaw) = upper(:counterparty)
              and t.categorizationStatus = com.ledgerflow.core.transaction.CategorizationStatus.CATEGORIZED
            group by c.code, c.name
            order by count(t) desc, c.code
            """)
    List<CounterpartyCategorization> summarizeCounterpartyHistory(UUID businessId, String counterparty);

    /** Transactions in a sibling account whose amount exactly offsets this one — the evidence an
     * internal transfer would leave behind. Returns candidates only; it asserts nothing. */
    @Query("""
            select t from Transaction t
            join fetch t.account
            left join fetch t.coaEntry
            left join fetch t.vendor
            where t.business.id = :businessId
              and t.id <> :transactionId
              and t.account.id <> :accountId
              and t.amount = :offsettingAmount
              and t.bookedDate between :from and :to
            order by t.bookedDate
            """)
    List<Transaction> findOffsettingInOtherAccounts(UUID businessId, UUID transactionId, UUID accountId,
                                                    BigDecimal offsettingAmount, LocalDate from, LocalDate to);

    @Query("""
            select t from Transaction t
            join fetch t.account
            left join fetch t.coaEntry
            left join fetch t.vendor
            where t.business.id = :businessId
              and t.id <> :transactionId
              and upper(t.counterpartyRaw) = upper(:counterparty)
              and t.amount = :amount
              and t.bookedDate between :from and :to
            order by t.bookedDate
            """)
    List<Transaction> findSameCounterpartyAndAmount(UUID businessId, UUID transactionId, String counterparty,
                                                    BigDecimal amount, LocalDate from, LocalDate to);

    @Query("""
            select t from Transaction t
            join fetch t.account
            left join fetch t.coaEntry
            left join fetch t.vendor
            where t.business.id = :businessId
              and upper(t.counterpartyRaw) = upper(:counterparty)
            order by t.bookedDate desc
            """)
    List<Transaction> findByCounterparty(UUID businessId, String counterparty, Pageable pageable);
}
