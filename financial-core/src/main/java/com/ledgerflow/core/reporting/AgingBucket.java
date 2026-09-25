package com.ledgerflow.core.reporting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public enum AgingBucket {

    CURRENT("Current", Long.MIN_VALUE, 0),
    ONE_TO_THIRTY("1-30 days", 1, 30),
    THIRTY_ONE_TO_SIXTY("31-60 days", 31, 60),
    SIXTY_ONE_TO_NINETY("61-90 days", 61, 90),
    OVER_NINETY("90+ days", 91, Long.MAX_VALUE);

    private final String label;
    private final long minimumDaysPastDue;
    private final long maximumDaysPastDue;

    AgingBucket(String label, long minimumDaysPastDue, long maximumDaysPastDue) {
        this.label = label;
        this.minimumDaysPastDue = minimumDaysPastDue;
        this.maximumDaysPastDue = maximumDaysPastDue;
    }

    public String label() {
        return label;
    }

    public static AgingBucket of(LocalDate dueDate, LocalDate asOf) {
        long daysPastDue = ChronoUnit.DAYS.between(dueDate, asOf);
        for (AgingBucket bucket : values()) {
            if (daysPastDue >= bucket.minimumDaysPastDue && daysPastDue <= bucket.maximumDaysPastDue) {
                return bucket;
            }
        }
        return OVER_NINETY;
    }
}
