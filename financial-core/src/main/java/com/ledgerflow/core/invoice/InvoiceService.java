package com.ledgerflow.core.invoice;

import com.ledgerflow.core.common.PagedResponse;
import com.ledgerflow.core.common.ResourceNotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final Clock clock;

    public InvoiceService(InvoiceRepository invoiceRepository, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PagedResponse<InvoiceResponse> search(UUID businessId, UUID customerId, InvoiceStatus status,
                                                 Pageable pageable) {
        LocalDate today = LocalDate.now(clock);
        return PagedResponse.from(invoiceRepository.search(businessId, customerId, status, pageable),
                invoice -> InvoiceResponse.from(invoice, today));
    }

    @Transactional(readOnly = true)
    public InvoiceDetailResponse findDetailById(UUID id) {
        return invoiceRepository.findDetailById(id)
                .map(invoice -> InvoiceDetailResponse.from(invoice, LocalDate.now(clock)))
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", id));
    }
}
