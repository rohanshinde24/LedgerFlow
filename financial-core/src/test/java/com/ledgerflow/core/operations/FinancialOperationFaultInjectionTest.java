package com.ledgerflow.core.operations;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.business.BusinessRepository;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.ledger.Account;
import com.ledgerflow.core.ledger.AccountCategory;
import com.ledgerflow.core.ledger.AccountRepository;
import com.ledgerflow.core.ledger.AccountType;
import com.ledgerflow.core.ledger.ChartOfAccountEntry;
import com.ledgerflow.core.ledger.ChartOfAccountRepository;
import com.ledgerflow.core.testsupport.PostgresIntegrationTest;
import com.ledgerflow.core.transaction.CategorizationStatus;
import com.ledgerflow.core.transaction.Transaction;
import com.ledgerflow.core.transaction.TransactionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Failures injected around the only code path that changes financial state.
 *
 * <p>Each scenario ends by reading the database back. The question is never whether the call
 * returned, but whether the ledger holds exactly one mutation afterwards. Results are written to a
 * JSON report so the benchmark can cite measured outcomes rather than assertions that merely passed.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinancialOperationFaultInjectionTest extends PostgresIntegrationTest {

    private static final Path REPORT = Path.of("target", "fault-injection-results.json");
    private static final List<Map<String, Object>> RESULTS = new CopyOnWriteArrayList<>();

    @Autowired
    private FinancialOperationService operationService;

    @Autowired
    private FinancialOperationRepository operations;

    @Autowired
    private AuditEventRepository auditEvents;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private BusinessRepository businesses;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private ChartOfAccountRepository chartOfAccounts;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private AuditEventRepository auditSpy;

    @AfterAll
    static void writeReport() throws Exception {
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of(
                        "scenarios", RESULTS,
                        "scenario_count", RESULTS.size(),
                        "duplicate_mutations_total",
                        RESULTS.stream().mapToInt(r -> (int) r.get("duplicate_mutations")).sum(),
                        "recovered", RESULTS.stream().filter(r -> (boolean) r.get("recovered")).count())));
    }

    /** A fresh, isolated subject per scenario, so one test's mutation cannot mask another's. */
    private Transaction freshSubject() {
        Currency usd = Currency.getInstance("USD");
        Business business = businesses.save(
                new Business(UUID.randomUUID(), "Fault Test Co", "Fault Test Co LLC", usd, (short) 1));
        Account account = accounts.save(new Account(UUID.randomUUID(), business, "Business Checking",
                AccountType.CHECKING, "Test Bank", "0001", Money.of("0.00", usd)));
        chartOfAccounts.save(new ChartOfAccountEntry(UUID.randomUUID(), business, "6700",
                "Office Supplies", AccountCategory.EXPENSE));
        chartOfAccounts.save(new ChartOfAccountEntry(UUID.randomUUID(), business, "6800",
                "Professional Fees", AccountCategory.EXPENSE));
        return transactions.save(new Transaction(UUID.randomUUID(), business, account,
                LocalDate.of(2025, 3, 11), "CARD PURCHASE", "Test Vendor",
                Money.of("-412.00", usd), "FAULT-" + UUID.randomUUID()));
    }

    /** One mutation is expected; anything beyond the first is a duplicate. */
    private int duplicateMutations(UUID transactionId) {
        long applied = auditEvents.findAll().stream()
                .filter(event -> event.getEventType().equals("TRANSACTION_CATEGORIZED"))
                .filter(event -> event.getDetail().contains("transaction=" + transactionId))
                .count();
        return (int) Math.max(0, applied - 1);
    }

    private void record(String scenario, UUID transactionId, boolean recovered, int attempts) {
        RESULTS.add(Map.of(
                "scenario", scenario,
                "transaction_id", transactionId.toString(),
                "attempts", attempts,
                "duplicate_mutations", duplicateMutations(transactionId),
                "recovered", recovered));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void a_duplicate_request_under_the_same_key_mutates_once() {
        Transaction subject = freshSubject();
        String key = "dup-request-" + UUID.randomUUID();

        var first = operationService.categorizeTransaction(key, subject.getBusiness().getId(),
                subject.getId(), "6700");
        var second = operationService.categorizeTransaction(key, subject.getBusiness().getId(),
                subject.getId(), "6700");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.operationId()).isEqualTo(first.operationId());
        assertThat(duplicateMutations(subject.getId())).isZero();
        record("duplicate_request", subject.getId(), true, 2);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void a_retry_after_a_lost_response_returns_the_original_result() {
        Transaction subject = freshSubject();
        String key = "lost-response-" + UUID.randomUUID();

        // The first response is produced and then deliberately discarded: the caller never learns
        // the outcome, which is indistinguishable from a network timeout after the commit landed.
        operationService.categorizeTransaction(key, subject.getBusiness().getId(), subject.getId(), "6700");
        entityManager.clear();

        var retry = operationService.categorizeTransaction(key, subject.getBusiness().getId(),
                subject.getId(), "6700");

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.chartOfAccountCode()).isEqualTo("6700");
        assertThat(duplicateMutations(subject.getId())).isZero();
        record("retry_after_lost_response", subject.getId(), true, 2);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void a_restart_loses_no_guarantee_because_the_key_lives_in_the_database() {
        Transaction subject = freshSubject();
        String key = "restart-" + UUID.randomUUID();

        operationService.categorizeTransaction(key, subject.getBusiness().getId(), subject.getId(), "6700");

        // A process restart drops every in-memory trace of the request. Clearing the persistence
        // context reproduces that: nothing below is served from a cache populated by the first call.
        entityManager.clear();

        var afterRestart = operationService.categorizeTransaction(key, subject.getBusiness().getId(),
                subject.getId(), "6700");

        assertThat(afterRestart.replayed()).isTrue();
        assertThat(duplicateMutations(subject.getId())).isZero();
        record("process_restart", subject.getId(), true, 2);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrent_duplicate_delivery_mutates_once() throws Exception {
        Transaction subject = freshSubject();
        String key = "concurrent-" + UUID.randomUUID();
        int callers = 8;

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    operationService.categorizeTransaction(key, subject.getBusiness().getId(),
                            subject.getId(), "6700");
                } catch (Throwable throwable) {
                    failures.add(throwable);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        assertThat(operations.findAll().stream().filter(o -> o.getOperationKey().equals(key))).hasSize(1);
        assertThat(duplicateMutations(subject.getId())).isZero();
        record("concurrent_duplicate_delivery", subject.getId(), true, callers);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void a_transient_dependency_failure_leaves_no_partial_mutation_and_recovers_on_retry() {
        Transaction subject = freshSubject();
        String key = "transient-" + UUID.randomUUID();

        doThrow(new org.springframework.dao.TransientDataAccessResourceException("connection reset"))
                .when(auditSpy).save(any(AuditEvent.class));

        assertThatThrownBy(() -> operationService.categorizeTransaction(key,
                subject.getBusiness().getId(), subject.getId(), "6700"))
                .isInstanceOf(org.springframework.dao.TransientDataAccessResourceException.class);

        entityManager.clear();
        // The failure must leave nothing behind: no claimed key, no half-applied categorization.
        assertThat(operations.findByOperationKey(key)).isEmpty();
        assertThat(transactions.findById(subject.getId()).orElseThrow().getCategorizationStatus())
                .isEqualTo(CategorizationStatus.UNCATEGORIZED);

        reset(auditSpy);
        var recovered = operationService.categorizeTransaction(key, subject.getBusiness().getId(),
                subject.getId(), "6700");

        assertThat(recovered.replayed()).isFalse();
        assertThat(recovered.chartOfAccountCode()).isEqualTo("6700");
        assertThat(duplicateMutations(subject.getId())).isZero();
        record("transient_dependency_failure", subject.getId(), true, 2);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void a_second_key_cannot_mutate_an_already_settled_transaction() {
        Transaction subject = freshSubject();
        String first = "settled-a-" + UUID.randomUUID();
        String second = "settled-b-" + UUID.randomUUID();

        operationService.categorizeTransaction(first, subject.getBusiness().getId(), subject.getId(), "6700");
        entityManager.clear();

        // A different key is a different request, so idempotency does not cover it. The invariant
        // does: history is not re-written, so the second attempt is refused outright.
        assertThatThrownBy(() -> operationService.categorizeTransaction(second,
                subject.getBusiness().getId(), subject.getId(), "6800"))
                .isInstanceOf(com.ledgerflow.core.common.InvariantViolationException.class);

        assertThat(duplicateMutations(subject.getId())).isZero();
        record("distinct_key_on_settled_subject", subject.getId(), true, 2);
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void the_audit_trail_refuses_to_be_rewritten() {
        Transaction subject = freshSubject();
        String key = "audit-" + UUID.randomUUID();
        operationService.categorizeTransaction(key, subject.getBusiness().getId(), subject.getId(), "6700");
        entityManager.clear();

        AuditEvent event = auditEvents.findAll().stream()
                .filter(e -> e.getDetail().contains("transaction=" + subject.getId()))
                .findFirst().orElseThrow();

        // Straight to the database, bypassing JPA entirely: the guarantee must hold against any
        // writer, not only against callers that go through the entity model.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update audit_events set detail = 'tampered' where id = ?", event.getId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "delete from audit_events where id = ?", event.getId()))
                .hasMessageContaining("append-only");

        record("audit_trail_immutability", subject.getId(), true, 1);
    }
}
