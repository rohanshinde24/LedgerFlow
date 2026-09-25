package com.ledgerflow.core.synthetic;

import com.ledgerflow.core.business.Business;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "ground_truth_labels")
public class GroundTruthLabel {

    public static final String EXPECTED_COA_CODE = "expected_coa_code";
    public static final String EXPECTED_INVOICE_NUMBERS = "expected_invoice_numbers";
    public static final String DUPLICATE_OF_EXTERNAL_REF = "duplicate_of_external_ref";
    public static final String DUPLICATE_OF_INVOICE_NUMBER = "duplicate_of_invoice_number";

    public static final String NO_EXPECTED_INVOICE = "NONE";

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @Column(name = "dataset_seed", nullable = false)
    private long datasetSeed;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "label_key", nullable = false)
    private String labelKey;

    @Column(name = "label_value", nullable = false)
    private String labelValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty_tag")
    private DifficultyTag difficultyTag;

    protected GroundTruthLabel() {
    }

    public GroundTruthLabel(UUID id, Business business, long datasetSeed, SubjectType subjectType, UUID subjectId,
                            String labelKey, String labelValue, DifficultyTag difficultyTag) {
        this.id = id;
        this.business = business;
        this.datasetSeed = datasetSeed;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.labelKey = labelKey;
        this.labelValue = labelValue;
        this.difficultyTag = difficultyTag;
    }

    public UUID getId() {
        return id;
    }

    public long getDatasetSeed() {
        return datasetSeed;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public String getLabelKey() {
        return labelKey;
    }

    public String getLabelValue() {
        return labelValue;
    }

    public DifficultyTag getDifficultyTag() {
        return difficultyTag;
    }
}
