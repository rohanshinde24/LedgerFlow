package com.ledgerflow.core.synthetic;

import com.ledgerflow.core.ledger.AccountCategory;

import java.util.List;

final class SyntheticBusinessProfile {

    record ChartOfAccountSeed(String code, String name, AccountCategory category) {
    }

    record RecurringExpense(String vendorName, String description, String coaCode, int dayOfMonth,
                            double baseAmount, double variance) {
    }

    record OccasionalExpense(String vendorName, String description, String coaCode,
                             double minAmount, double maxAmount, int maxPerMonth) {
    }

    static final String BUSINESS_NAME = "Northwind Studio";
    static final String BUSINESS_LEGAL_NAME = "Northwind Studio LLC";

    static final String CHECKING_COA = "1000";
    static final String SAVINGS_COA = "1010";
    static final String RECEIVABLES_COA = "1200";
    static final String OFFICE_SUPPLIES_COA = "6700";
    static final String PROFESSIONAL_FEES_COA = "6800";
    static final String SOFTWARE_COA = "6000";

    static final List<ChartOfAccountSeed> CHART_OF_ACCOUNTS = List.of(
            new ChartOfAccountSeed(CHECKING_COA, "Business Checking", AccountCategory.ASSET),
            new ChartOfAccountSeed(SAVINGS_COA, "Business Savings", AccountCategory.ASSET),
            new ChartOfAccountSeed(RECEIVABLES_COA, "Accounts Receivable", AccountCategory.ASSET),
            new ChartOfAccountSeed("2000", "Credit Card Payable", AccountCategory.LIABILITY),
            new ChartOfAccountSeed("3000", "Owner Equity", AccountCategory.EQUITY),
            new ChartOfAccountSeed("4000", "Consulting Revenue", AccountCategory.REVENUE),
            new ChartOfAccountSeed("4100", "Retainer Revenue", AccountCategory.REVENUE),
            new ChartOfAccountSeed(SOFTWARE_COA, "Software Subscriptions", AccountCategory.EXPENSE),
            new ChartOfAccountSeed("6100", "Contractor Payments", AccountCategory.EXPENSE),
            new ChartOfAccountSeed("6200", "Payroll", AccountCategory.EXPENSE),
            new ChartOfAccountSeed("6300", "Rent", AccountCategory.EXPENSE),
            new ChartOfAccountSeed("6400", "Travel", AccountCategory.EXPENSE),
            new ChartOfAccountSeed("6500", "Meals and Entertainment", AccountCategory.EXPENSE),
            new ChartOfAccountSeed("6600", "Advertising", AccountCategory.EXPENSE),
            new ChartOfAccountSeed(OFFICE_SUPPLIES_COA, "Office Supplies", AccountCategory.EXPENSE),
            new ChartOfAccountSeed(PROFESSIONAL_FEES_COA, "Professional Fees", AccountCategory.EXPENSE),
            new ChartOfAccountSeed("6900", "Bank Fees", AccountCategory.EXPENSE));

    static final List<String> CUSTOMER_NAMES = List.of(
            "Ardent Health Partners",
            "Brightwater Foods",
            "Calder Manufacturing",
            "Dunmore Logistics",
            "Evercrest Insurance",
            "Foxglove Media Group",
            "Granite Peak Outfitters",
            "Halcyon Robotics");

    static final List<RecurringExpense> RECURRING_EXPENSES = List.of(
            new RecurringExpense("WeWork", "WEWORK MONTHLY MEMBERSHIP", "6300", 1, 2850.00, 0),
            new RecurringExpense("Figma", "FIGMA MONTHLY SUBSCRIPTION", SOFTWARE_COA, 3, 144.00, 0),
            new RecurringExpense("Slack", "SLACK TECHNOLOGIES BILLING", SOFTWARE_COA, 5, 87.50, 0),
            new RecurringExpense("Amazon Web Services", "AWS CLOUD SERVICES", SOFTWARE_COA, 7, 640.00, 260.00),
            new RecurringExpense("Google Workspace", "GOOGLE WORKSPACE BILLING", SOFTWARE_COA, 9, 72.00, 0),
            new RecurringExpense("Gusto", "GUSTO PAYROLL RUN", "6200", 15, 18400.00, 900.00),
            new RecurringExpense("Gusto", "GUSTO PAYROLL RUN", "6200", 28, 18400.00, 900.00),
            new RecurringExpense("First Cascade Bank", "MONTHLY SERVICE CHARGE", "6900", 26, 35.00, 0));

    static final List<OccasionalExpense> OCCASIONAL_EXPENSES = List.of(
            new OccasionalExpense("Marta Reyes Design", "CONTRACTOR INVOICE PAYOUT", "6100", 1800.00, 4500.00, 2),
            new OccasionalExpense("Meta Platforms", "META ADS BILLING", "6600", 320.00, 1450.00, 2),
            new OccasionalExpense("Google Ads", "GOOGLE ADS BILLING", "6600", 280.00, 1200.00, 2),
            new OccasionalExpense("United Airlines", "UNITED AIR TICKET", "6400", 240.00, 890.00, 1),
            new OccasionalExpense("Marriott", "MARRIOTT HOTELS STAY", "6400", 190.00, 640.00, 1),
            new OccasionalExpense("Blue Bottle Coffee", "BLUE BOTTLE COFFEE", "6500", 12.00, 68.00, 3),
            new OccasionalExpense("Chipotle", "CHIPOTLE ONLINE ORDER", "6500", 24.00, 120.00, 2),
            new OccasionalExpense("Reed and Foster", "REED FOSTER LEGAL FEES", PROFESSIONAL_FEES_COA, 450.00, 2200.00, 1));

    static final String AMBIGUOUS_VENDOR_NAME = "Amazon";
    static final String AMBIGUOUS_VENDOR_DESCRIPTION = "AMZN MKTP US*2K4J91";
    static final String UNUSUAL_VENDOR_NAME = "Cascade Metal Works";
    static final String UNUSUAL_VENDOR_DESCRIPTION = "CASCADE METAL WORKS INVOICE";

    private SyntheticBusinessProfile() {
    }
}
