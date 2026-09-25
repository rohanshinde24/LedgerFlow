package com.ledgerflow.core.reference;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ReferenceDataController {

    private final ReferenceDataService referenceDataService;

    public ReferenceDataController(ReferenceDataService referenceDataService) {
        this.referenceDataService = referenceDataService;
    }

    @GetMapping("/accounts")
    public List<AccountResponse> listAccounts(@RequestParam UUID businessId) {
        return referenceDataService.listAccounts(businessId);
    }

    @GetMapping("/chart-of-accounts")
    public List<ChartOfAccountResponse> listChartOfAccounts(@RequestParam UUID businessId) {
        return referenceDataService.listChartOfAccounts(businessId);
    }

    @GetMapping("/customers")
    public List<CustomerResponse> listCustomers(@RequestParam UUID businessId) {
        return referenceDataService.listCustomers(businessId);
    }

    @GetMapping("/vendors")
    public List<VendorResponse> listVendors(@RequestParam UUID businessId) {
        return referenceDataService.listVendors(businessId);
    }
}
