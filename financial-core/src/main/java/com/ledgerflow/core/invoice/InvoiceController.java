package com.ledgerflow.core.invoice;

import com.ledgerflow.core.common.PagedResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private static final int MAX_PAGE_SIZE = 200;

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping
    public PagedResponse<InvoiceResponse> search(
            @RequestParam UUID businessId,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "issueDate").and(Sort.by("invoiceNumber")));
        return invoiceService.search(businessId, customerId, status, pageRequest);
    }

    @GetMapping("/{id}")
    public InvoiceDetailResponse findById(@PathVariable UUID id) {
        return invoiceService.findDetailById(id);
    }
}
