package com.ledgerflow.core.reporting;

import com.ledgerflow.core.common.MoneyView;

import java.time.LocalDate;
import java.util.List;

public record ExpensesByCategoryResponse(LocalDate from,
                                         LocalDate to,
                                         MoneyView total,
                                         List<CategoryTotal> categories) {

    public record CategoryTotal(String chartOfAccountCode, String chartOfAccountName, MoneyView amount,
                                long transactionCount) {
    }
}
