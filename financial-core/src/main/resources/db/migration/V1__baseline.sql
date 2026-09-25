create table businesses (
    id            uuid primary key,
    name          varchar(200) not null,
    legal_name    varchar(200) not null,
    currency      varchar(3)      not null,
    fiscal_year_start_month smallint not null check (fiscal_year_start_month between 1 and 12),
    created_at    timestamptz  not null default now()
);

create table accounts (
    id                  uuid primary key,
    business_id         uuid         not null references businesses (id),
    name                varchar(200) not null,
    account_type        varchar(32)  not null,
    institution         varchar(200) not null,
    account_number_mask varchar(8)   not null,
    currency            varchar(3)      not null,
    opening_balance     numeric(19, 4) not null,
    created_at          timestamptz  not null default now()
);
create index idx_accounts_business on accounts (business_id);

create table chart_of_accounts (
    id          uuid primary key,
    business_id uuid         not null references businesses (id),
    code        varchar(16)  not null,
    name        varchar(200) not null,
    category    varchar(16)  not null,
    created_at  timestamptz  not null default now(),
    constraint uq_coa_business_code unique (business_id, code)
);

create table customers (
    id          uuid primary key,
    business_id uuid         not null references businesses (id),
    name        varchar(200) not null,
    email       varchar(200),
    created_at  timestamptz  not null default now()
);
create index idx_customers_business on customers (business_id);

create table vendors (
    id                  uuid primary key,
    business_id         uuid         not null references businesses (id),
    name                varchar(200) not null,
    default_coa_entry_id uuid        references chart_of_accounts (id),
    created_at          timestamptz  not null default now()
);
create index idx_vendors_business on vendors (business_id);

create table invoices (
    id             uuid primary key,
    business_id    uuid           not null references businesses (id),
    customer_id    uuid           not null references customers (id),
    invoice_number varchar(64)    not null,
    issue_date     date           not null,
    due_date       date           not null,
    currency       varchar(3)        not null,
    subtotal       numeric(19, 4) not null check (subtotal >= 0),
    tax_amount     numeric(19, 4) not null check (tax_amount >= 0),
    total_amount   numeric(19, 4) not null check (total_amount >= 0),
    amount_paid    numeric(19, 4) not null default 0 check (amount_paid >= 0),
    status         varchar(20)    not null,
    memo           varchar(500),
    created_at     timestamptz    not null default now(),
    constraint uq_invoice_business_number unique (business_id, invoice_number),
    constraint ck_invoice_not_overpaid check (amount_paid <= total_amount),
    constraint ck_invoice_due_after_issue check (due_date >= issue_date)
);
create index idx_invoices_business_status on invoices (business_id, status);
create index idx_invoices_customer on invoices (customer_id);

create table invoice_lines (
    id           uuid primary key,
    invoice_id   uuid           not null references invoices (id) on delete cascade,
    line_number  integer        not null check (line_number > 0),
    description  varchar(300)   not null,
    quantity     numeric(19, 4) not null check (quantity > 0),
    unit_price   numeric(19, 4) not null check (unit_price >= 0),
    line_total   numeric(19, 4) not null check (line_total >= 0),
    constraint uq_invoice_line_number unique (invoice_id, line_number)
);

create table transactions (
    id                    uuid primary key,
    business_id           uuid           not null references businesses (id),
    account_id            uuid           not null references accounts (id),
    booked_date           date           not null,
    description           varchar(300)   not null,
    counterparty_raw      varchar(200)   not null,
    amount                numeric(19, 4) not null,
    currency              varchar(3)        not null,
    coa_entry_id          uuid           references chart_of_accounts (id),
    vendor_id             uuid           references vendors (id),
    categorization_status varchar(20)    not null,
    categorization_source varchar(20),
    external_ref          varchar(64)    not null,
    created_at            timestamptz    not null default now(),
    constraint ck_transaction_amount_nonzero check (amount <> 0),
    constraint ck_transaction_categorized_has_coa
        check (categorization_status <> 'CATEGORIZED' or coa_entry_id is not null)
);
create index idx_transactions_business_date on transactions (business_id, booked_date);
create index idx_transactions_account on transactions (account_id);
create index idx_transactions_status on transactions (business_id, categorization_status);

create table payments (
    id             uuid primary key,
    business_id    uuid           not null references businesses (id),
    customer_id    uuid           references customers (id),
    transaction_id uuid           references transactions (id),
    received_date  date           not null,
    amount         numeric(19, 4) not null check (amount > 0),
    currency       varchar(3)        not null,
    method         varchar(20)    not null,
    reference      varchar(200)   not null,
    payer_name_raw varchar(200)   not null,
    status         varchar(20)    not null,
    created_at     timestamptz    not null default now()
);
create index idx_payments_business_date on payments (business_id, received_date);
create index idx_payments_status on payments (business_id, status);

create table reconciliation_matches (
    id             uuid primary key,
    business_id    uuid           not null references businesses (id),
    payment_id     uuid           not null references payments (id),
    invoice_id     uuid           not null references invoices (id),
    amount_applied numeric(19, 4) not null check (amount_applied > 0),
    status         varchar(20)    not null,
    method         varchar(24)    not null,
    confidence     numeric(5, 4)  check (confidence between 0 and 1),
    created_at     timestamptz    not null default now(),
    confirmed_at   timestamptz,
    constraint uq_match_payment_invoice unique (payment_id, invoice_id)
);
create index idx_matches_payment on reconciliation_matches (payment_id);
create index idx_matches_invoice on reconciliation_matches (invoice_id);

create table ground_truth_labels (
    id             uuid primary key,
    business_id    uuid         not null references businesses (id),
    dataset_seed   bigint       not null,
    subject_type   varchar(24)  not null,
    subject_id     uuid         not null,
    label_key      varchar(48)  not null,
    label_value    varchar(200) not null,
    difficulty_tag varchar(48),
    constraint uq_ground_truth_subject_key unique (subject_type, subject_id, label_key)
);
create index idx_ground_truth_lookup on ground_truth_labels (business_id, subject_type, label_key);
create index idx_ground_truth_difficulty on ground_truth_labels (business_id, difficulty_tag);
