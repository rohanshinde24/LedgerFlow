package com.ledgerflow.core.transaction;

import com.ledgerflow.core.synthetic.SyntheticDatasetGenerator;
import com.ledgerflow.core.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.function.Predicate;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransactionInvestigationApiIntegrationTest extends PostgresIntegrationTest {

    private static final long SEED = 20250101L;
    private static final LocalDate END_DATE = LocalDate.of(2025, 12, 31);
    private static final String OUTBOUND_TRANSFER = "TRANSFER TO SAVINGS *9082";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SyntheticDatasetGenerator generator;
    @Autowired
    private TransactionRepository transactionRepository;

    private UUID businessId;

    @BeforeAll
    void generateDataset() {
        businessId = generator.generate(SEED, END_DATE).businessId();
    }

    @Test
    void surfacesTheBalancingEntryForAnInternalTransfer() throws Exception {
        Transaction outbound = firstMatching(t -> t.getDescription().equals(OUTBOUND_TRANSFER));

        mockMvc.perform(get("/api/transactions/{id}/offsetting-candidates", outbound.getId())
                        .param("windowDays", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount.amount").value(outbound.getAmount().amount().negate().toPlainString()))
                .andExpect(jsonPath("$[0].bookedDate").value(outbound.getBookedDate().toString()))
                .andExpect(jsonPath("$[0].accountId").value(not(
                        outbound.getAccount().getId().toString())));
    }

    @Test
    void reportsNoHistoryForACounterpartyThatWasNeverCategorized() throws Exception {
        Transaction outbound = firstMatching(t -> t.getDescription().equals(OUTBOUND_TRANSFER));

        mockMvc.perform(get("/api/transactions/{id}/counterparty-history", outbound.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterparty").value(outbound.getCounterpartyRaw()))
                .andExpect(jsonPath("$.categorizedCount").value(0))
                .andExpect(jsonPath("$.consistentlyCategorized").value(false))
                .andExpect(jsonPath("$.codes").isEmpty());
    }

    @Test
    void summarizesHowACategorizedCounterpartyWasTreatedBefore() throws Exception {
        Transaction categorized = firstMatching(
                t -> t.getCategorizationStatus() == CategorizationStatus.CATEGORIZED && t.getCoaEntry() != null);

        mockMvc.perform(get("/api/transactions/{id}/counterparty-history", categorized.getId())
                        .param("exampleLimit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counterparty").value(categorized.getCounterpartyRaw()))
                .andExpect(jsonPath("$.categorizedCount").value(greaterThan(0)))
                .andExpect(jsonPath("$.codes").isNotEmpty())
                .andExpect(jsonPath("$.codes[0].chartOfAccountCode").isString())
                .andExpect(jsonPath("$.codes[0].transactionCount").isNumber())
                .andExpect(jsonPath("$.recentExamples.length()").value(
                        lessThanOrEqualTo(3)));
    }

    @Test
    void neverReturnsTheSubjectTransactionAmongItsOwnCounterpartyExamples() throws Exception {
        Transaction categorized = firstMatching(
                t -> t.getCategorizationStatus() == CategorizationStatus.CATEGORIZED && t.getCoaEntry() != null);

        mockMvc.perform(get("/api/transactions/{id}/counterparty-history", categorized.getId())
                        .param("exampleLimit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recentExamples[*].id").value(
                        not(hasItem(categorized.getId().toString()))));
    }

    @Test
    void similarCandidatesShareTheCounterpartyAndAmountButNotTheIdentity() throws Exception {
        Transaction outbound = firstMatching(t -> t.getDescription().equals(OUTBOUND_TRANSFER));

        mockMvc.perform(get("/api/transactions/{id}/similar", outbound.getId())
                        .param("windowDays", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(
                        not(hasItem(outbound.getId().toString()))))
                .andExpect(jsonPath("$[*].counterparty").value(
                        everyItem(equalTo(
                                outbound.getCounterpartyRaw()))))
                .andExpect(jsonPath("$[*].amount.amount").value(
                        everyItem(equalTo(
                                outbound.getAmount().amount().toPlainString()))));
    }

    @Test
    void clampsAnOverlyWideInvestigationWindow() throws Exception {
        Transaction outbound = firstMatching(t -> t.getDescription().equals(OUTBOUND_TRANSFER));

        mockMvc.perform(get("/api/transactions/{id}/offsetting-candidates", outbound.getId())
                        .param("windowDays", "9999"))
                .andExpect(status().isOk());
    }

    @Test
    void returnsNotFoundWhenInvestigatingAnUnknownTransaction() throws Exception {
        mockMvc.perform(get("/api/transactions/{id}/counterparty-history", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    private Transaction firstMatching(Predicate<Transaction> predicate) {
        return transactionRepository.findAll().stream()
                .filter(t -> t.getBusiness().getId().equals(businessId))
                .filter(predicate)
                .findFirst()
                .orElseThrow();
    }
}
