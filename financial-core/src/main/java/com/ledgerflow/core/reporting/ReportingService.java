package com.ledgerflow.core.reporting;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.business.BusinessRepository;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.common.MoneyView;
import com.ledgerflow.core.common.ResourceNotFoundException;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.invoice.InvoiceRepository;
import com.ledgerflow.core.invoice.InvoiceStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReportingService {

    private final ReportingRepository reportingRepository;
    private final BusinessRepository businessRepository;
    private final InvoiceRepository invoiceRepository;

    public ReportingService(ReportingRepository reportingRepository, BusinessRepository businessRepository,
                            InvoiceRepository invoiceRepository) {
        this.reportingRepository = reportingRepository;
        this.businessRepository = businessRepository;
        this.invoiceRepository = invoiceRepository;
    }

    @Transactional(readOnly = true)
    public ExpensesByCategoryResponse expensesByCategory(UUID businessId, LocalDate from, LocalDate to) {
        Currency currency = currencyOf(businessId);
        List<ReportingRepository.CategoryTotal> rows =
                reportingRepository.aggregateExpensesByCategory(businessId, from, to);

        List<ExpensesByCategoryResponse.CategoryTotal> categories = rows.stream()
                .map(row -> new ExpensesByCategoryResponse.CategoryTotal(
                        row.getCode(),
                        row.getName(),
                        MoneyView.of(spendOf(row, currency)),
                        row.getTransactionCount()))
                .toList();

        Money total = rows.stream()
                .map(row -> spendOf(row, currency))
                .reduce(Money.zero(currency), Money::plus);

        return new ExpensesByCategoryResponse(from, to, MoneyView.of(total), categories);
    }

    @Transactional(readOnly = true)
    public CashFlowResponse cashFlow(UUID businessId, LocalDate from, LocalDate to) {
        Currency currency = currencyOf(businessId);
        List<CashFlowResponse.Period> periods = reportingRepository.aggregateMonthlyCashFlow(businessId, from, to)
                .stream()
                .map(row -> {
                    Money inflow = Money.of(row.getInflow(), currency);
                    Money outflow = Money.of(row.getOutflow(), currency).negated();
                    return new CashFlowResponse.Period(
                            YearMonth.of(row.getPeriodYear(), row.getPeriodMonth()),
                            MoneyView.of(inflow),
                            MoneyView.of(outflow),
                            MoneyView.of(inflow.minus(outflow)));
                })
                .toList();
        return new CashFlowResponse(from, to, periods);
    }

    @Transactional(readOnly = true)
    public ReceivablesSummaryResponse receivables(UUID businessId, LocalDate asOf) {
        Currency currency = currencyOf(businessId);
        List<Invoice> settleable = invoiceRepository.findByBusinessIdAndStatusIn(businessId,
                List.of(InvoiceStatus.OPEN, InvoiceStatus.PARTIALLY_PAID));

        Money outstanding = Money.zero(currency);
        Money overdue = Money.zero(currency);
        int overdueCount = 0;
        Map<String, List<Invoice>> byBucket = new LinkedHashMap<>();
        for (AgingBucket bucket : AgingBucket.values()) {
            byBucket.put(bucket.label(), new ArrayList<>());
        }

        for (Invoice invoice : settleable) {
            outstanding = outstanding.plus(invoice.getOutstandingAmount());
            if (invoice.isOverdueOn(asOf)) {
                overdue = overdue.plus(invoice.getOutstandingAmount());
                overdueCount++;
            }
            byBucket.get(AgingBucket.of(invoice.getDueDate(), asOf).label()).add(invoice);
        }

        List<ReceivablesSummaryResponse.AgingBucket> aging = byBucket.entrySet().stream()
                .map(entry -> new ReceivablesSummaryResponse.AgingBucket(
                        entry.getKey(),
                        MoneyView.of(entry.getValue().stream()
                                .map(Invoice::getOutstandingAmount)
                                .reduce(Money.zero(currency), Money::plus)),
                        entry.getValue().size()))
                .toList();

        return new ReceivablesSummaryResponse(asOf, MoneyView.of(outstanding), MoneyView.of(overdue),
                settleable.size(), overdueCount, aging);
    }

    private Money spendOf(ReportingRepository.CategoryTotal row, Currency currency) {
        return Money.of(row.getSignedTotal(), currency).negated();
    }

    private Currency currencyOf(UUID businessId) {
        return businessRepository.findById(businessId)
                .map(Business::getCurrency)
                .orElseThrow(() -> new ResourceNotFoundException("Business", businessId));
    }
}
