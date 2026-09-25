package com.ledgerflow.core.synthetic;

import com.ledgerflow.core.business.Business;
import com.ledgerflow.core.business.BusinessRepository;
import com.ledgerflow.core.common.Money;
import com.ledgerflow.core.invoice.Invoice;
import com.ledgerflow.core.invoice.InvoiceLine;
import com.ledgerflow.core.invoice.InvoiceRepository;
import com.ledgerflow.core.invoice.InvoiceStatus;
import com.ledgerflow.core.ledger.Account;
import com.ledgerflow.core.ledger.AccountRepository;
import com.ledgerflow.core.ledger.AccountType;
import com.ledgerflow.core.ledger.ChartOfAccountEntry;
import com.ledgerflow.core.ledger.ChartOfAccountRepository;
import com.ledgerflow.core.party.Customer;
import com.ledgerflow.core.party.CustomerRepository;
import com.ledgerflow.core.party.Vendor;
import com.ledgerflow.core.party.VendorRepository;
import com.ledgerflow.core.payment.Payment;
import com.ledgerflow.core.payment.PaymentMethod;
import com.ledgerflow.core.payment.PaymentRepository;
import com.ledgerflow.core.reconciliation.MatchMethod;
import com.ledgerflow.core.reconciliation.MatchStatus;
import com.ledgerflow.core.reconciliation.ReconciliationMatch;
import com.ledgerflow.core.reconciliation.ReconciliationMatchRepository;
import com.ledgerflow.core.transaction.CategorizationSource;
import com.ledgerflow.core.transaction.Transaction;
import com.ledgerflow.core.transaction.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

@Service
public class SyntheticDatasetGenerator {

    public record GeneratedDataset(UUID businessId, long seed, LocalDate startDate, LocalDate endDate,
                                   int transactionCount, int invoiceCount, int paymentCount, int matchCount,
                                   int groundTruthLabelCount) {
    }

    static final int MONTHS = 12;
    static final int WORKING_SET_FROM_MONTH = 9;

    private final BusinessRepository businessRepository;
    private final AccountRepository accountRepository;
    private final ChartOfAccountRepository chartOfAccountRepository;
    private final CustomerRepository customerRepository;
    private final VendorRepository vendorRepository;
    private final InvoiceRepository invoiceRepository;
    private final TransactionRepository transactionRepository;
    private final PaymentRepository paymentRepository;
    private final ReconciliationMatchRepository matchRepository;
    private final GroundTruthLabelRepository groundTruthLabelRepository;

    public SyntheticDatasetGenerator(BusinessRepository businessRepository,
                                     AccountRepository accountRepository,
                                     ChartOfAccountRepository chartOfAccountRepository,
                                     CustomerRepository customerRepository,
                                     VendorRepository vendorRepository,
                                     InvoiceRepository invoiceRepository,
                                     TransactionRepository transactionRepository,
                                     PaymentRepository paymentRepository,
                                     ReconciliationMatchRepository matchRepository,
                                     GroundTruthLabelRepository groundTruthLabelRepository) {
        this.businessRepository = businessRepository;
        this.accountRepository = accountRepository;
        this.chartOfAccountRepository = chartOfAccountRepository;
        this.customerRepository = customerRepository;
        this.vendorRepository = vendorRepository;
        this.invoiceRepository = invoiceRepository;
        this.transactionRepository = transactionRepository;
        this.paymentRepository = paymentRepository;
        this.matchRepository = matchRepository;
        this.groundTruthLabelRepository = groundTruthLabelRepository;
    }

    @Transactional
    public GeneratedDataset generate(long seed, LocalDate endDate) {
        return new DatasetBuilder(seed, endDate).build();
    }

    private enum SettlementBehavior {
        PAY_ON_TIME,
        PAY_LATE,
        PAY_PARTIAL,
        UNPAID
    }

    private record PaymentPlan(Invoice invoice, SettlementBehavior behavior, LocalDate paymentDate, Money amount) {
    }

    private record LineSpec(BigDecimal quantity, Money unitPrice) {
    }

    private final class DatasetBuilder {

        private final long seed;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final LocalDate workingSetStart;
        private final Random random;
        private final Currency currency = Currency.getInstance("USD");

        private final Map<String, ChartOfAccountEntry> coaByCode = new LinkedHashMap<>();
        private final Map<String, Vendor> vendorsByName = new LinkedHashMap<>();
        private final List<Customer> customers = new ArrayList<>();
        private final List<Invoice> invoices = new ArrayList<>();
        private final List<Transaction> transactions = new ArrayList<>();
        private final List<Payment> payments = new ArrayList<>();
        private final List<ReconciliationMatch> matches = new ArrayList<>();
        private final List<GroundTruthLabel> labels = new ArrayList<>();
        private final List<PaymentPlan> paymentPlans = new ArrayList<>();
        private final Map<UUID, Money> appliedByInvoice = new LinkedHashMap<>();
        private final Map<UUID, Money> appliedByPayment = new LinkedHashMap<>();

        private Business business;
        private Account checkingAccount;
        private Account savingsAccount;
        private int externalRefSequence;
        private int invoiceSequence;

        private DatasetBuilder(long seed, LocalDate endDate) {
            this.seed = seed;
            this.endDate = endDate;
            this.startDate = endDate.withDayOfMonth(1).minusMonths(MONTHS - 1L);
            this.workingSetStart = startDate.plusMonths(WORKING_SET_FROM_MONTH);
            this.random = new Random(seed);
        }

        private GeneratedDataset build() {
            createBusiness();
            createChartOfAccounts();
            createAccounts();
            createCustomers();
            createVendors();

            for (int month = 0; month < MONTHS; month++) {
                LocalDate monthStart = startDate.plusMonths(month);
                recordRecurringExpenses(month, monthStart);
                recordOccasionalExpenses(monthStart);
                recordInternalTransfer(monthStart);
                recordAmbiguousMerchantCharge(month, monthStart);
                recordCategorizationEdgeCases(month, monthStart);
                issueInvoices(month, monthStart);
                issueReconciliationEdgeCases(month, monthStart);
            }

            paymentPlans.forEach(this::settle);
            persist();

            return new GeneratedDataset(business.getId(), seed, startDate, endDate, transactions.size(),
                    invoices.size(), payments.size(), matches.size(), labels.size());
        }

        private void createBusiness() {
            business = new Business(nextId(), SyntheticBusinessProfile.BUSINESS_NAME,
                    SyntheticBusinessProfile.BUSINESS_LEGAL_NAME, currency, (short) 1);
        }

        private void createChartOfAccounts() {
            SyntheticBusinessProfile.CHART_OF_ACCOUNTS.forEach(seedEntry -> coaByCode.put(seedEntry.code(),
                    new ChartOfAccountEntry(nextId(), business, seedEntry.code(), seedEntry.name(),
                            seedEntry.category())));
        }

        private void createAccounts() {
            checkingAccount = new Account(nextId(), business, "Business Checking", AccountType.CHECKING,
                    "First Cascade Bank", "4417", money(48_500.00));
            savingsAccount = new Account(nextId(), business, "Business Savings", AccountType.SAVINGS,
                    "First Cascade Bank", "9082", money(120_000.00));
        }

        private void createCustomers() {
            SyntheticBusinessProfile.CUSTOMER_NAMES.forEach(name -> customers.add(new Customer(nextId(), business, name,
                    "ap@" + name.toLowerCase().replaceAll("[^a-z]", "") + ".com")));
        }

        private void createVendors() {
            SyntheticBusinessProfile.RECURRING_EXPENSES
                    .forEach(expense -> registerVendor(expense.vendorName(), expense.coaCode()));
            SyntheticBusinessProfile.OCCASIONAL_EXPENSES
                    .forEach(expense -> registerVendor(expense.vendorName(), expense.coaCode()));
            registerVendor(SyntheticBusinessProfile.AMBIGUOUS_VENDOR_NAME, null);
            registerVendor(SyntheticBusinessProfile.UNUSUAL_VENDOR_NAME, null);
        }

        private void registerVendor(String name, String defaultCoaCode) {
            vendorsByName.computeIfAbsent(name, vendorName -> new Vendor(nextId(), business, vendorName,
                    defaultCoaCode == null ? null : coaByCode.get(defaultCoaCode)));
        }

        private void recordRecurringExpenses(int month, LocalDate monthStart) {
            for (SyntheticBusinessProfile.RecurringExpense expense : SyntheticBusinessProfile.RECURRING_EXPENSES) {
                LocalDate date = dayOf(monthStart, expense.dayOfMonth());
                if (date.isAfter(endDate)) {
                    continue;
                }
                boolean increasedSubscription = expense.vendorName().equals("Google Workspace") && month >= 8;
                double baseAmount = increasedSubscription ? 96.00 : expense.baseAmount();
                Money amount = expense.variance() == 0
                        ? money(baseAmount)
                        : randomMoney(baseAmount - expense.variance(), baseAmount + expense.variance());

                Transaction transaction = spend(date, expense.description(), expense.vendorName(), amount);
                DifficultyTag tag = increasedSubscription && month == 8 ? DifficultyTag.SUBSCRIPTION_INCREASE : null;
                classify(transaction, expense.coaCode(), tag);

                if (month == 2 && expense.vendorName().equals("Figma")) {
                    Transaction duplicate = spend(date, expense.description(), expense.vendorName(), amount);
                    classify(duplicate, expense.coaCode(), DifficultyTag.DUPLICATE_TRANSACTION);
                    label(SubjectType.TRANSACTION, duplicate.getId(), GroundTruthLabel.DUPLICATE_OF_EXTERNAL_REF,
                            transaction.getExternalRef(), DifficultyTag.DUPLICATE_TRANSACTION);
                }
            }
        }

        private void recordOccasionalExpenses(LocalDate monthStart) {
            for (SyntheticBusinessProfile.OccasionalExpense expense : SyntheticBusinessProfile.OCCASIONAL_EXPENSES) {
                int occurrences = randomInt(0, expense.maxPerMonth());
                for (int index = 0; index < occurrences; index++) {
                    LocalDate date = dayOf(monthStart, randomInt(1, 28));
                    if (date.isAfter(endDate)) {
                        continue;
                    }
                    Money amount = randomMoney(expense.minAmount(), expense.maxAmount());
                    classify(spend(date, expense.description(), expense.vendorName(), amount), expense.coaCode(), null);
                }
            }
        }

        private void recordInternalTransfer(LocalDate monthStart) {
            LocalDate date = dayOf(monthStart, 20);
            if (date.isAfter(endDate)) {
                return;
            }
            Money amount = money(5_000.00);
            Transaction outbound = transaction(checkingAccount, date, "TRANSFER TO SAVINGS *9082",
                    "Internal Transfer", amount.negated());
            Transaction inbound = transaction(savingsAccount, date, "TRANSFER FROM CHECKING *4417",
                    "Internal Transfer", amount);
            label(SubjectType.TRANSACTION, outbound.getId(), GroundTruthLabel.EXPECTED_COA_CODE,
                    SyntheticBusinessProfile.SAVINGS_COA, DifficultyTag.INTERNAL_TRANSFER);
            label(SubjectType.TRANSACTION, inbound.getId(), GroundTruthLabel.EXPECTED_COA_CODE,
                    SyntheticBusinessProfile.CHECKING_COA, DifficultyTag.INTERNAL_TRANSFER);
        }

        private void recordAmbiguousMerchantCharge(int month, LocalDate monthStart) {
            if (month % 3 != 0) {
                return;
            }
            LocalDate date = dayOf(monthStart, randomInt(6, 24));
            if (date.isAfter(endDate)) {
                return;
            }
            Transaction transaction = spend(date, SyntheticBusinessProfile.AMBIGUOUS_VENDOR_DESCRIPTION,
                    SyntheticBusinessProfile.AMBIGUOUS_VENDOR_NAME, randomMoney(38.00, 420.00));
            label(SubjectType.TRANSACTION, transaction.getId(), GroundTruthLabel.EXPECTED_COA_CODE,
                    SyntheticBusinessProfile.OFFICE_SUPPLIES_COA, DifficultyTag.AMBIGUOUS_MERCHANT);
        }

        private void recordCategorizationEdgeCases(int month, LocalDate monthStart) {
            if (month == 4) {
                LocalDate date = dayOf(monthStart, 12);
                Transaction refund = transaction(checkingAccount, date, "FIGMA SUBSCRIPTION REFUND", "Figma",
                        money(144.00));
                label(SubjectType.TRANSACTION, refund.getId(), GroundTruthLabel.EXPECTED_COA_CODE,
                        SyntheticBusinessProfile.SOFTWARE_COA, DifficultyTag.REFUND);
            }
            if (month == 5) {
                LocalDate date = dayOf(monthStart, 18);
                Transaction unusual = spend(date, SyntheticBusinessProfile.UNUSUAL_VENDOR_DESCRIPTION,
                        SyntheticBusinessProfile.UNUSUAL_VENDOR_NAME, money(4_200.00));
                label(SubjectType.TRANSACTION, unusual.getId(), GroundTruthLabel.EXPECTED_COA_CODE,
                        SyntheticBusinessProfile.PROFESSIONAL_FEES_COA, DifficultyTag.UNUSUAL_VENDOR);
            }
        }

        private void issueInvoices(int month, LocalDate monthStart) {
            int invoiceCount = randomInt(3, 5);
            for (int index = 0; index < invoiceCount; index++) {
                Customer customer = customers.get(random.nextInt(customers.size()));
                LocalDate issueDate = dayOf(monthStart, randomInt(1, 26));
                if (issueDate.isAfter(endDate)) {
                    continue;
                }
                planSettlement(createInvoice(customer, issueDate, randomInt(1, 3), null));
            }
        }

        private void issueReconciliationEdgeCases(int month, LocalDate monthStart) {
            switch (month) {
                case 9 -> {
                    LocalDate issueDate = dayOf(monthStart, 4);
                    Money identicalSubtotal = money(9_600.00);
                    Invoice first = createInvoiceWithSubtotal(customers.get(0), issueDate, identicalSubtotal);
                    Invoice second = createInvoiceWithSubtotal(customers.get(1), issueDate.plusDays(1),
                            identicalSubtotal);
                    tag(SubjectType.INVOICE, first.getId(), DifficultyTag.IDENTICAL_INVOICE_AMOUNTS);
                    tag(SubjectType.INVOICE, second.getId(), DifficultyTag.IDENTICAL_INVOICE_AMOUNTS);
                    plan(second, SettlementBehavior.PAY_ON_TIME, second.getDueDate().minusDays(2),
                            second.getTotalAmount());
                }
                case 10 -> {
                    LocalDate issueDate = dayOf(monthStart, 3);
                    Customer customer = customers.get(2);
                    Invoice first = createInvoice(customer, issueDate, 2, null);
                    Invoice second = createInvoice(customer, issueDate.plusDays(2), 1, null);
                    recordMultiInvoicePayment(first, second, issueDate.plusDays(24));

                    Invoice original = createInvoice(customers.get(3), issueDate.plusDays(5), 2, null);
                    Invoice duplicate = duplicateOf(original);
                    label(SubjectType.INVOICE, duplicate.getId(), GroundTruthLabel.DUPLICATE_OF_INVOICE_NUMBER,
                            original.getInvoiceNumber(), DifficultyTag.DUPLICATE_INVOICE);
                    plan(original, SettlementBehavior.PAY_ON_TIME, original.getDueDate().minusDays(3),
                            original.getTotalAmount());

                    Invoice variationInvoice = createInvoice(customers.get(4), issueDate.plusDays(7), 2, null);
                    recordNameVariationPayment(variationInvoice, variationInvoice.getDueDate().minusDays(1));
                }
                case 11 -> {
                    LocalDate date = dayOf(monthStart, 12);
                    recordOrphanPayment(date, "STRIPE PAYOUT 8842", "STRIPE PAYOUTS", money(3_180.00),
                            DifficultyTag.UNMATCHED_PAYMENT);
                    recordOrphanPayment(date.plusDays(4), "ACH REF INV-9999-404", "Brightwater Foods",
                            money(7_450.00), DifficultyTag.MISSING_INVOICE);
                }
                default -> {
                }
            }
        }

        private Invoice createInvoice(Customer customer, LocalDate issueDate, int lineCount, String memo) {
            List<LineSpec> specs = new ArrayList<>();
            for (int index = 0; index < lineCount; index++) {
                specs.add(new LineSpec(BigDecimal.valueOf(randomInt(8, 60)), randomMoney(120.00, 220.00)));
            }
            return assembleInvoice(customer, issueDate, memo, specs);
        }

        private Invoice createInvoiceWithSubtotal(Customer customer, LocalDate issueDate, Money subtotal) {
            return assembleInvoice(customer, issueDate, null, List.of(new LineSpec(BigDecimal.ONE, subtotal)));
        }

        private Invoice assembleInvoice(Customer customer, LocalDate issueDate, String memo, List<LineSpec> specs) {
            Money subtotal = specs.stream()
                    .map(spec -> spec.unitPrice().multipliedBy(spec.quantity()))
                    .reduce(Money.zero(currency), Money::plus);
            Invoice invoice = new Invoice(nextId(), business, customer, nextInvoiceNumber(issueDate), issueDate,
                    issueDate.plusDays(30), subtotal, Money.zero(currency), InvoiceStatus.OPEN, memo);

            int lineNumber = 1;
            for (LineSpec spec : specs) {
                invoice.addLine(new InvoiceLine(nextId(), invoice, lineNumber++, "Professional services",
                        spec.quantity(), spec.unitPrice()));
            }
            invoices.add(invoice);
            return invoice;
        }

        private Invoice duplicateOf(Invoice original) {
            Invoice duplicate = new Invoice(nextId(), business, original.getCustomer(),
                    original.getInvoiceNumber() + "-A", original.getIssueDate().plusDays(1),
                    original.getDueDate().plusDays(1), original.getSubtotal(), original.getTaxAmount(),
                    InvoiceStatus.OPEN, "Re-issued copy");
            duplicate.addLine(new InvoiceLine(nextId(), duplicate, 1, "Professional services", BigDecimal.ONE,
                    original.getSubtotal()));
            invoices.add(duplicate);
            return duplicate;
        }

        private void planSettlement(Invoice invoice) {
            SettlementBehavior behavior = pickBehavior();
            switch (behavior) {
                case PAY_ON_TIME -> plan(invoice, behavior, invoice.getDueDate().plusDays(randomInt(-6, 4)),
                        invoice.getTotalAmount());
                case PAY_LATE -> plan(invoice, behavior, invoice.getDueDate().plusDays(randomInt(21, 55)),
                        invoice.getTotalAmount());
                case PAY_PARTIAL -> plan(invoice, behavior, invoice.getDueDate().plusDays(randomInt(-2, 8)),
                        invoice.getTotalAmount().multipliedBy(BigDecimal.valueOf(randomInt(40, 70))
                                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_EVEN)));
                case UNPAID -> {
                }
            }
        }

        private SettlementBehavior pickBehavior() {
            int roll = random.nextInt(100);
            if (roll < 70) {
                return SettlementBehavior.PAY_ON_TIME;
            }
            if (roll < 80) {
                return SettlementBehavior.PAY_LATE;
            }
            if (roll < 90) {
                return SettlementBehavior.PAY_PARTIAL;
            }
            return SettlementBehavior.UNPAID;
        }

        private void plan(Invoice invoice, SettlementBehavior behavior, LocalDate paymentDate, Money amount) {
            paymentPlans.add(new PaymentPlan(invoice, behavior, paymentDate, amount));
        }

        private void settle(PaymentPlan plan) {
            if (plan.paymentDate().isAfter(endDate) || plan.paymentDate().isBefore(startDate)) {
                return;
            }
            Invoice invoice = plan.invoice();
            Payment payment = createPayment(invoice.getCustomer(), plan.paymentDate(), plan.amount(),
                    "ACH REF " + invoice.getInvoiceNumber(), invoice.getCustomer().getName());

            label(SubjectType.PAYMENT, payment.getId(), GroundTruthLabel.EXPECTED_INVOICE_NUMBERS,
                    invoice.getInvoiceNumber(), difficultyOf(plan));

            if (isHistorical(plan.paymentDate())) {
                confirmMatch(payment, invoice, plan.amount());
            }
        }

        private DifficultyTag difficultyOf(PaymentPlan plan) {
            return switch (plan.behavior()) {
                case PAY_LATE -> DifficultyTag.LATE_PAYMENT;
                case PAY_PARTIAL -> DifficultyTag.PARTIAL_PAYMENT;
                default -> null;
            };
        }

        private void recordMultiInvoicePayment(Invoice first, Invoice second, LocalDate paymentDate) {
            Money amount = first.getTotalAmount().plus(second.getTotalAmount());
            Payment payment = createPayment(first.getCustomer(), paymentDate, amount, "ACH REF MULTIPLE INVOICES",
                    first.getCustomer().getName());
            label(SubjectType.PAYMENT, payment.getId(), GroundTruthLabel.EXPECTED_INVOICE_NUMBERS,
                    first.getInvoiceNumber() + "," + second.getInvoiceNumber(), DifficultyTag.MULTI_INVOICE_PAYMENT);
        }

        private void recordNameVariationPayment(Invoice invoice, LocalDate paymentDate) {
            String variation = invoice.getCustomer().getName().toUpperCase().replace("HEALTH", "HLTH")
                    .replace("INSURANCE", "INS") + " AP DEPT";
            Payment payment = createPayment(null, paymentDate, invoice.getTotalAmount(), "WIRE CREDIT", variation);
            label(SubjectType.PAYMENT, payment.getId(), GroundTruthLabel.EXPECTED_INVOICE_NUMBERS,
                    invoice.getInvoiceNumber(), DifficultyTag.CUSTOMER_NAME_VARIATION);
        }

        private void recordOrphanPayment(LocalDate date, String reference, String payerName, Money amount,
                                         DifficultyTag tag) {
            Payment payment = createPayment(null, date, amount, reference, payerName);
            label(SubjectType.PAYMENT, payment.getId(), GroundTruthLabel.EXPECTED_INVOICE_NUMBERS,
                    GroundTruthLabel.NO_EXPECTED_INVOICE, tag);
        }

        private Payment createPayment(Customer customer, LocalDate date, Money amount, String reference,
                                      String payerName) {
            Transaction deposit = transaction(checkingAccount, date, "DEPOSIT " + reference, payerName, amount);
            deposit.categorize(coaByCode.get(SyntheticBusinessProfile.RECEIVABLES_COA), null,
                    CategorizationSource.IMPORT);
            label(SubjectType.TRANSACTION, deposit.getId(), GroundTruthLabel.EXPECTED_COA_CODE,
                    SyntheticBusinessProfile.RECEIVABLES_COA, null);

            Payment payment = new Payment(nextId(), business, customer, deposit, date, amount, PaymentMethod.ACH,
                    reference, payerName);
            payments.add(payment);
            return payment;
        }

        private void confirmMatch(Payment payment, Invoice invoice, Money amount) {
            matches.add(new ReconciliationMatch(nextId(), payment, invoice, amount, MatchStatus.CONFIRMED,
                    MatchMethod.EXACT, BigDecimal.ONE));
            appliedByInvoice.merge(invoice.getId(), amount, Money::plus);
            appliedByPayment.merge(payment.getId(), amount, Money::plus);
        }

        private boolean isHistorical(LocalDate date) {
            return date.isBefore(workingSetStart);
        }

        private Transaction spend(LocalDate date, String description, String vendorName, Money amount) {
            return transaction(checkingAccount, date, description, vendorName, amount.abs().negated());
        }

        private Transaction transaction(Account account, LocalDate date, String description, String counterparty,
                                        Money amount) {
            Transaction transaction = new Transaction(nextId(), business, account, date, description, counterparty,
                    amount, nextExternalRef());
            transactions.add(transaction);
            return transaction;
        }

        private void classify(Transaction transaction, String coaCode, DifficultyTag tag) {
            label(SubjectType.TRANSACTION, transaction.getId(), GroundTruthLabel.EXPECTED_COA_CODE, coaCode, tag);
            if (tag == null && isHistorical(transaction.getBookedDate())) {
                transaction.categorize(coaByCode.get(coaCode), vendorsByName.get(transaction.getCounterpartyRaw()),
                        CategorizationSource.IMPORT);
            }
        }

        private void label(SubjectType subjectType, UUID subjectId, String key, String value, DifficultyTag tag) {
            labels.add(new GroundTruthLabel(nextId(), business, seed, subjectType, subjectId, key, value, tag));
        }

        private void tag(SubjectType subjectType, UUID subjectId, DifficultyTag tag) {
            label(subjectType, subjectId, "difficulty", tag.name(), tag);
        }

        private void persist() {
            // Allocations must be applied before saving: entities carry assigned identifiers, so save() merges a
            // managed copy and later mutations of these instances would never reach the database.
            invoices.stream()
                    .filter(invoice -> appliedByInvoice.containsKey(invoice.getId()))
                    .forEach(invoice -> invoice.applyConfirmedAllocations(appliedByInvoice.get(invoice.getId())));
            payments.stream()
                    .filter(payment -> appliedByPayment.containsKey(payment.getId()))
                    .forEach(payment -> payment.applyConfirmedAllocations(appliedByPayment.get(payment.getId())));

            businessRepository.save(business);
            chartOfAccountRepository.saveAll(coaByCode.values());
            accountRepository.saveAll(List.of(checkingAccount, savingsAccount));
            customerRepository.saveAll(customers);
            vendorRepository.saveAll(vendorsByName.values());
            invoiceRepository.saveAll(invoices);
            transactionRepository.saveAll(transactions);
            paymentRepository.saveAll(payments);
            matchRepository.saveAll(matches);
            groundTruthLabelRepository.saveAll(labels);
        }

        private String nextInvoiceNumber(LocalDate issueDate) {
            return "INV-%d-%03d".formatted(issueDate.getYear(), ++invoiceSequence);
        }

        private String nextExternalRef() {
            return "BANKTXN-%06d".formatted(++externalRefSequence);
        }

        private LocalDate dayOf(LocalDate monthStart, int dayOfMonth) {
            return monthStart.withDayOfMonth(Math.min(dayOfMonth, monthStart.lengthOfMonth()));
        }

        private Money money(double value) {
            return Money.of(BigDecimal.valueOf(value), currency);
        }

        private Money randomMoney(double minimum, double maximum) {
            double value = minimum + random.nextDouble() * (maximum - minimum);
            return Money.of(BigDecimal.valueOf(value), currency);
        }

        private int randomInt(int minimumInclusive, int maximumInclusive) {
            return minimumInclusive + random.nextInt(maximumInclusive - minimumInclusive + 1);
        }

        private UUID nextId() {
            return new UUID(random.nextLong(), random.nextLong());
        }
    }
}
