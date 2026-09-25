package com.ledgerflow.core.ledger;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChartOfAccountRepository extends JpaRepository<ChartOfAccountEntry, UUID> {

    List<ChartOfAccountEntry> findByBusinessIdOrderByCode(UUID businessId);

    Optional<ChartOfAccountEntry> findByBusinessIdAndCode(UUID businessId, String code);
}
