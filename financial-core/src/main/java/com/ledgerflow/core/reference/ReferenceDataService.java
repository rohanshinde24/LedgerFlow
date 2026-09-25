package com.ledgerflow.core.reference;

import com.ledgerflow.core.common.MoneyView;
import com.ledgerflow.core.ledger.AccountRepository;
import com.ledgerflow.core.ledger.ChartOfAccountRepository;
import com.ledgerflow.core.party.CustomerRepository;
import com.ledgerflow.core.party.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ReferenceDataService {

    private final AccountRepository accountRepository;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final CustomerRepository customerRepository;
    private final VendorRepository vendorRepository;

    public ReferenceDataService(AccountRepository accountRepository,
                                ChartOfAccountRepository chartOfAccountRepository,
                                CustomerRepository customerRepository,
                                VendorRepository vendorRepository) {
        this.accountRepository = accountRepository;
        this.chartOfAccountRepository = chartOfAccountRepository;
        this.customerRepository = customerRepository;
        this.vendorRepository = vendorRepository;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(UUID businessId) {
        return accountRepository.findByBusinessIdOrderByName(businessId).stream()
                .map(account -> new AccountResponse(account.getId(), account.getName(), account.getAccountType(),
                        account.getInstitution(), account.getAccountNumberMask(),
                        MoneyView.of(account.getOpeningBalance())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChartOfAccountResponse> listChartOfAccounts(UUID businessId) {
        return chartOfAccountRepository.findByBusinessIdOrderByCode(businessId).stream()
                .map(entry -> new ChartOfAccountResponse(entry.getId(), entry.getCode(), entry.getName(),
                        entry.getCategory()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> listCustomers(UUID businessId) {
        return customerRepository.findByBusinessIdOrderByName(businessId).stream()
                .map(customer -> new CustomerResponse(customer.getId(), customer.getName(), customer.getEmail()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VendorResponse> listVendors(UUID businessId) {
        return vendorRepository.findByBusinessIdOrderByName(businessId).stream()
                .map(vendor -> new VendorResponse(vendor.getId(), vendor.getName(),
                        vendor.getDefaultCoaEntry() == null ? null : vendor.getDefaultCoaEntry().getCode(),
                        vendor.getDefaultCoaEntry() == null ? null : vendor.getDefaultCoaEntry().getName()))
                .toList();
    }
}
