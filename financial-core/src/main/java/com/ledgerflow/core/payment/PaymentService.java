package com.ledgerflow.core.payment;

import com.ledgerflow.core.common.PagedResponse;
import com.ledgerflow.core.common.ResourceNotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PaymentResponse> search(UUID businessId, PaymentStatus status, Pageable pageable) {
        return PagedResponse.from(paymentRepository.search(businessId, status, pageable), PaymentResponse::from);
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(UUID id) {
        return paymentRepository.findDetailById(id)
                .map(PaymentResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }
}
