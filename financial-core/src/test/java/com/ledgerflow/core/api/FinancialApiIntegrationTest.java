package com.ledgerflow.core.api;

import com.ledgerflow.core.payment.PaymentRepository;
import com.ledgerflow.core.payment.PaymentStatus;
import com.ledgerflow.core.synthetic.SyntheticDatasetGenerator;
import com.ledgerflow.core.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinancialApiIntegrationTest extends PostgresIntegrationTest {

    private static final long SEED = 20250101L;
    private static final LocalDate END_DATE = LocalDate.of(2025, 12, 31);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SyntheticDatasetGenerator generator;
    @Autowired
    private PaymentRepository paymentRepository;

    private UUID businessId;

    @BeforeAll
    void generateDataset() {
        businessId = generator.generate(SEED, END_DATE).businessId();
    }

    @Test
    void listsTransactionsFilteredByCategorizationStatus() throws Exception {
        mockMvc.perform(get("/api/transactions")
                        .param("businessId", businessId.toString())
                        .param("categorizationStatus", "UNCATEGORIZED")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.totalItems").isNumber())
                .andExpect(jsonPath("$.items[0].amount.amount").isString())
                .andExpect(jsonPath("$.items[0].categorizationStatus").value("UNCATEGORIZED"));
    }

    @Test
    void returnsNotFoundForAnUnknownTransaction() throws Exception {
        mockMvc.perform(get("/api/transactions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void reportsExpensesByCategoryAsDecimalStrings() throws Exception {
        mockMvc.perform(get("/api/reports/expenses-by-category")
                        .param("businessId", businessId.toString())
                        .param("from", "2025-01-01")
                        .param("to", "2025-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isNotEmpty())
                .andExpect(jsonPath("$.total.amount").isString())
                .andExpect(jsonPath("$.total.currency").value("USD"));
    }

    @Test
    void reportsMonthlyCashFlow() throws Exception {
        mockMvc.perform(get("/api/reports/cash-flow")
                        .param("businessId", businessId.toString())
                        .param("from", "2025-01-01")
                        .param("to", "2025-12-31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods").isNotEmpty())
                .andExpect(jsonPath("$.periods[0].inflow.amount").isString());
    }

    @Test
    void exposesReconciliationCandidatesForAnUnappliedPayment() throws Exception {
        UUID paymentId = paymentRepository.findAll().stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.UNAPPLIED)
                .findFirst()
                .orElseThrow()
                .getId();

        mockMvc.perform(get("/api/reconciliation/candidates").param("paymentId", paymentId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.consideredInvoiceCount").isNumber())
                .andExpect(jsonPath("$.ambiguous").isBoolean());
    }

    @Test
    void rejectsARequestMissingTheBusinessIdentifier() throws Exception {
        mockMvc.perform(get("/api/transactions"))
                .andExpect(status().isBadRequest());
    }
}
