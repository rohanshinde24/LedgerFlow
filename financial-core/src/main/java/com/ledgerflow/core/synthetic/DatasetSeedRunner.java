package com.ledgerflow.core.synthetic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ledgerflow.dataset.generate-on-startup", havingValue = "true")
public class DatasetSeedRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatasetSeedRunner.class);

    private final SyntheticDatasetGenerator generator;
    private final GroundTruthLabelRepository groundTruthLabelRepository;
    private final List<Long> seeds;
    private final LocalDate endDate;

    public DatasetSeedRunner(SyntheticDatasetGenerator generator,
                             GroundTruthLabelRepository groundTruthLabelRepository,
                             @Value("${ledgerflow.dataset.seeds}") List<Long> seeds,
                             @Value("${ledgerflow.dataset.end-date}") LocalDate endDate) {
        this.generator = generator;
        this.groundTruthLabelRepository = groundTruthLabelRepository;
        this.seeds = seeds;
        this.endDate = endDate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Each seed is its own business with its own ground truth, so a held-out evaluation set can
        // sit beside the development set without either being regenerated or contaminated.
        for (long seed : seeds) {
            if (groundTruthLabelRepository.existsByDatasetSeed(seed)) {
                log.info("Synthetic dataset for seed={} already present; skipping generation", seed);
                continue;
            }
            SyntheticDatasetGenerator.GeneratedDataset dataset = generator.generate(seed, endDate);
            log.info("Generated synthetic dataset business={} seed={} transactions={} invoices={} payments={} matches={} labels={}",
                    dataset.businessId(), dataset.seed(), dataset.transactionCount(), dataset.invoiceCount(),
                    dataset.paymentCount(), dataset.matchCount(), dataset.groundTruthLabelCount());
        }
    }
}
