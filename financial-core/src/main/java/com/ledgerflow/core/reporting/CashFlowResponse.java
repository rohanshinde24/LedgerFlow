package com.ledgerflow.core.reporting;

import com.ledgerflow.core.common.MoneyView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record CashFlowResponse(LocalDate from, LocalDate to, List<Period> periods) {

    public record Period(YearMonth month, MoneyView inflow, MoneyView outflow, MoneyView net) {
    }
}
