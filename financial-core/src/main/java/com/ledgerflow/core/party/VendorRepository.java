package com.ledgerflow.core.party;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    List<Vendor> findByBusinessIdOrderByName(UUID businessId);
}
