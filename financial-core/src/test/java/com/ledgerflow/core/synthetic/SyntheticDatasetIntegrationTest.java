package com.ledgerflow.core.synthetic;

import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.invoice.InvoiceRepository;
import com.ledgerflow.core.invoice.InvoiceStatus;
import com.ledgerflow.core.payment.Payment;
import com.ledgerflow.core.payment.PaymentRepository;
import com.ledgerflow.core.payment.PaymentStatus;
import com.ledgerflow.core.reconciliation.MatchStatus;
import com.ledgerflow.core.reconciliation.ReconciliationCandidateSet;
import com.ledgerflow.core.reconciliation.ReconciliationMatch;
import com.ledgerflow.core.reconciliation.ReconciliationMatchRepository;
import com.ledgerflow.core.reconciliation.ReconciliationService;
import com.ledgerflow.core.testsupport.PostgresIntegrationTest;
import com.ledgerflow.core.transaction.CategorizationStatus;
import com.ledgerflow.core.transaction.Transaction;
import com.ledgerflow.core.transaction.TransactionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyntheticDatasetIntegrationTest extends PostgresIntegrationTest {

    private static final long SEED = 20250101L;
    private static final LocalDate END_DATE = LocalDate.of(2025, 12, 31);

    private SyntheticDatasetGenerator.GeneratedDataset dataset;

    @Autowired
    private SyntheticDatasetGenerator generator;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private ReconciliationMatchRepository matchRepository;
    @Autowired
    private GroundTruthLabelRepository groundTruthLabelRepository;
    @Autowired
    private ReconciliationService reconciliationService;

    @BeforeAll
    void generateDataset() {
        dataset = generator.generate(SEED, END_DATE);
    }

    @Test
    void producesAWorkingSetAlongsideFullyReconciledHistory() {
        List<Transaction> transactions = transactionRepository.findAll();
        assertThat(transactions).isNotEmpty();
        assertThat(transactions).anyMatch(t -> t.getCategorizationStatus() == CategorizationStatus.CATEGORIZED);
        assertThat(transactions).anyMatch(t -> t.getCategorizationStatus() == CategorizationStatus.UNCATEGORIZED);
        assertThat(paymentRepository.findAll()).anyMatch(p -> p.getStatus() == PaymentStatus.UNAPPLIED);
    }

    @Test
    @Transactional(readOnly = true)
    void everyInvoicePaidAmountEqualsTheSumOfItsConfirmedMatches() {
        Map<UUID, List<ReconciliationMatch>> matchesByInvoice = matchRepository.findAll().stream()
                .filter(match -> match.getStatus() == MatchStatus.CONFIRMED)
                .collect(Collectors.groupingBy(match -> match.getInvoice().getId()));

        for (Invoice invoice : invoiceRepository.findAll()) {
            Money confirmed = matchesByInvoice.getOrDefault(invoice.getId(), List.of()).stream()
                    .map(ReconciliationMatch::getAmountApplied)
                    .reduce(Money.zero(invoice.getCurrency()), Money::plus);

            assertThat(invoice.getAmountPaid()).as("invoice %s", invoice.getInvoiceNumber()).isEqualTo(confirmed);
            assertThat(invoice.getAmountPaid().isGreaterThan(invoice.getTotalAmount())).isFalse();
            assertThat(invoice.getStatus()).isEqualTo(expectedStatus(invoice));
        }
    }

    @Test
    @Transactional(readOnly = true)
    void noPaymentIsAllocatedBeyondItsAmount() {
        Map<UUID, List<ReconciliationMatch>> matchesByPayment = matchRepository.findAll().stream()
                .filter(match -> match.getStatus() == MatchStatus.CONFIRMED)
                .collect(Collectors.groupingBy(match -> match.getPayment().getId()));

        for (Payment payment : paymentRepository.findAll()) {
            Money applied = matchesByPayment.getOrDefault(payment.getId(), List.of()).stream()
                    .map(ReconciliationMatch::getAmountApplied)
                    .reduce(Money.zero(payment.getCurrency()), Money::plus);

            assertThat(applied.isGreaterThan(payment.getAmount())).isFalse();
        }
    }

    @Test
    void labelsEveryTransactionAndInvoiceWithHiddenGroundTruth() {
        Set<UUID> labelledTransactions = subjectIds(dataset.businessId(), SubjectType.TRANSACTION,
                GroundTruthLabel.EXPECTED_COA_CODE);
        Set<UUID> labelledPayments = subjectIds(dataset.businessId(), SubjectType.PAYMENT,
                GroundTruthLabel.EXPECTED_INVOICE_NUMBERS);

        // Scoped to this dataset's business: the labels are, so the subjects compared against them
        // must be too, or unrelated rows in the same database would count as unlabelled.
        assertThat(labelledTransactions).containsAll(transactionRepository.findAll().stream()
                .filter(transaction -> transaction.getBusiness().getId().equals(dataset.businessId()))
                .map(Transaction::getId).toList());
        assertThat(labelledPayments).containsAll(paymentRepository.findAll().stream()
                .filter(payment -> payment.getBusiness().getId().equals(dataset.businessId()))
                .map(Payment::getId).toList());
    }

    @Test
    void coversTheDifficultyTaxonomyNeededForEvaluation() {
        Set<DifficultyTag> tags = groundTruthLabelRepository.findAll().stream()
                .filter(label -> label.getDifficultyTag() != null)
                .map(GroundTruthLabel::getDifficultyTag)
                .collect(Collectors.toSet());

        assertThat(dataset.groundTruthLabelCount()).isPositive();
        assertThat(tags).contains(DifficultyTag.DUPLICATE_TRANSACTION, DifficultyTag.PARTIAL_PAYMENT,
                DifficultyTag.MULTI_INVOICE_PAYMENT, DifficultyTag.IDENTICAL_INVOICE_AMOUNTS,
                DifficultyTag.CUSTOMER_NAME_VARIATION, DifficultyTag.INTERNAL_TRANSFER,
                DifficultyTag.AMBIGUOUS_MERCHANT, DifficultyTag.UNMATCHED_PAYMENT);
    }

    @Test
    void reducesCandidatesToTheReferencedInvoiceForUnappliedPayments() {
        Map<UUID, GroundTruthLabel> expectedByPayment = groundTruthLabelRepository
                .findByBusinessIdAndSubjectTypeAndLabelKey(dataset.businessId(), SubjectType.PAYMENT,
                        GroundTruthLabel.EXPECTED_INVOICE_NUMBERS)
                .stream()
                .collect(Collectors.toMap(GroundTruthLabel::getSubjectId, Function.identity()));

        List<Payment> unapplied = paymentRepository.findAll().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.UNAPPLIED)
                .filter(payment -> singleExpectedInvoice(expectedByPayment.get(payment.getId())) != null)
                .toList();

        assertThat(unapplied).isNotEmpty();

        long resolved = unapplied.stream()
                .filter(payment -> {
                    ReconciliationCandidateSet result = reconciliationService.findCandidates(payment.getId());
                    return result.topCandidate() != null && result.topCandidate().invoice().getInvoiceNumber()
                            .equals(singleExpectedInvoice(expectedByPayment.get(payment.getId())));
                })
                .count();

        assertThat(resolved).isGreaterThanOrEqualTo((long) Math.ceil(unapplied.size() * 0.8));
    }

    private String singleExpectedInvoice(GroundTruthLabel label) {
        if (label == null || GroundTruthLabel.NO_EXPECTED_INVOICE.equals(label.getLabelValue())
                || label.getLabelValue().contains(",")) {
            return null;
        }
        return label.getLabelValue();
    }

    private Set<UUID> subjectIds(UUID businessId, SubjectType subjectType, String labelKey) {
        return groundTruthLabelRepository.findByBusinessIdAndSubjectTypeAndLabelKey(businessId, subjectType, labelKey)
                .stream()
                .map(GroundTruthLabel::getSubjectId)
                .collect(Collectors.toSet());
    }

    private InvoiceStatus expectedStatus(Invoice invoice) {
        if (invoice.getAmountPaid().isZero()) {
            return InvoiceStatus.OPEN;
        }
        return invoice.getOutstandingAmount().isZero() ? InvoiceStatus.PAID : InvoiceStatus.PARTIALLY_PAID;
    }
}
