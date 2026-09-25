package com.ledgerflow.core.synthetic;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GroundTruthLabelRepository extends JpaRepository<GroundTruthLabel, UUID> {

    List<GroundTruthLabel> findByBusinessIdAndSubjectTypeAndLabelKey(UUID businessId, SubjectType subjectType,
                                                                    String labelKey);

    long countByBusinessId(UUID businessId);

    boolean existsByDatasetSeed(long datasetSeed);
}
