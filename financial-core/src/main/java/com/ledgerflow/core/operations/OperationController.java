package com.ledgerflow.core.operations;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/operations")
public class OperationController {

    private final FinancialOperationService operationService;

    public OperationController(FinancialOperationService operationService) {
        this.operationService = operationService;
    }

    @PostMapping("/categorize-transaction")
    public FinancialOperationService.OperationOutcome categorizeTransaction(
            @Valid @RequestBody CategorizeTransactionRequest request) {
        return operationService.categorizeTransaction(request.operationKey(), request.businessId(),
                request.transactionId(), request.chartOfAccountCode());
    }

    public record CategorizeTransactionRequest(
            @NotBlank @Size(max = 120) String operationKey,
            @NotNull UUID businessId,
            @NotNull UUID transactionId,
            @NotBlank @Pattern(regexp = "^[0-9]{4}$") String chartOfAccountCode) {
    }
}
