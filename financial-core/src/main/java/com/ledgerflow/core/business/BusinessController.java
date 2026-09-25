package com.ledgerflow.core.business;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessRepository businessRepository;

    public BusinessController(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    @GetMapping
    public List<BusinessResponse> listBusinesses() {
        return businessRepository.findAll().stream()
                .map(business -> new BusinessResponse(business.getId(), business.getName(), business.getLegalName(),
                        business.getCurrency().getCurrencyCode()))
                .toList();
    }

    public record BusinessResponse(UUID id, String name, String legalName, String currency) {
    }
}
