package com.ledgerflow.core.reporting;

import com.ledgerflow.core.transaction.Transaction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ReportingRepository extends Repository<Transaction, UUID> {

    interface CategoryTotal {
        String getCode();

        String getName();

        BigDecimal getSignedTotal();

        long getTransactionCount();
    }

    interface MonthlyCashFlow {
        int getPeriodYear();

        int getPeriodMonth();

        BigDecimal getInflow();

        BigDecimal getOutflow();
    }

    @Query("""
            select c.code as code,
                   c.name as name,
                   sum(t.amount) as signedTotal,
                   count(t) as transactionCount
            from Transaction t
            join t.coaEntry c
            where t.business.id = :businessId
              and t.bookedDate between :from and :to
              and c.category = com.ledgerflow.core.ledger.AccountCategory.EXPENSE
            group by c.code, c.name
            order by sum(t.amount) asc
            """)
    List<CategoryTotal> aggregateExpensesByCategory(UUID businessId, LocalDate from, LocalDate to);

    @Query("""
            select extract(year from t.bookedDate) as periodYear,
                   extract(month from t.bookedDate) as periodMonth,
                   sum(case when t.amount > 0 then t.amount else 0 end) as inflow,
                   sum(case when t.amount < 0 then t.amount else 0 end) as outflow
            from Transaction t
            where t.business.id = :businessId
              and t.bookedDate between :from and :to
            group by extract(year from t.bookedDate), extract(month from t.bookedDate)
            order by extract(year from t.bookedDate), extract(month from t.bookedDate)
            """)
    List<MonthlyCashFlow> aggregateMonthlyCashFlow(UUID businessId, LocalDate from, LocalDate to);
}
