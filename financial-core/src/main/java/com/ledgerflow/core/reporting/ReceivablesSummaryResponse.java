package com.ledgerflow.core.reporting;

import com.ledgerflow.core.common.MoneyView;

import java.time.LocalDate;
import java.util.List;

public record ReceivablesSummaryResponse(LocalDate asOf,
                                         MoneyView outstandingAmount,
                                         MoneyView overdueAmount,
                                         int openInvoiceCount,
                                         int overdueInvoiceCount,
                                         List<AgingBucket> aging) {

    public record AgingBucket(String label, MoneyView amount, int invoiceCount) {
    }
}
