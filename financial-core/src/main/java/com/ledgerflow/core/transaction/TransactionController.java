package com.ledgerflow.core.transaction;

import com.ledgerflow.core.common.PagedResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_EXAMPLES = 25;
    private static final int MAX_WINDOW_DAYS = 30;

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @GetMapping
    public PagedResponse<TransactionResponse> search(
            @RequestParam UUID businessId,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) CategorizationStatus categorizationStatus,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        TransactionQuery query = new TransactionQuery(businessId, accountId, from, to, categorizationStatus, q);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "bookedDate").and(Sort.by("externalRef")));
        return transactionService.search(query, pageRequest);
    }

    @GetMapping("/{id}")
    public TransactionResponse findById(@PathVariable UUID id) {
        return transactionService.findById(id);
    }

    @GetMapping("/{id}/counterparty-history")
    public CounterpartyHistoryResponse counterpartyHistory(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "5") int exampleLimit) {
        return transactionService.counterpartyHistory(id, Math.min(exampleLimit, MAX_EXAMPLES));
    }

    @GetMapping("/{id}/offsetting-candidates")
    public List<TransactionResponse> offsettingCandidates(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "3") int windowDays) {
        return transactionService.offsettingCandidates(id, Math.min(windowDays, MAX_WINDOW_DAYS));
    }

    @GetMapping("/{id}/similar")
    public List<TransactionResponse> similar(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "7") int windowDays) {
        return transactionService.similarCandidates(id, Math.min(windowDays, MAX_WINDOW_DAYS));
    }
}
