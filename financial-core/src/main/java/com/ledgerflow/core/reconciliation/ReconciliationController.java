package com.ledgerflow.core.reconciliation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/candidates")
    public ReconciliationCandidateSetResponse findCandidates(@RequestParam UUID paymentId) {
        return ReconciliationCandidateSetResponse.from(paymentId, reconciliationService.findCandidates(paymentId));
    }
}
