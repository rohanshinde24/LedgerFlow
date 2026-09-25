create table financial_operations (
    id             uuid primary key,
    business_id    uuid         not null references businesses (id),
    operation_key  varchar(120) not null,
    operation_type varchar(40)  not null,
    subject_type   varchar(24)  not null,
    subject_id     uuid         not null,
    status         varchar(20)  not null,
    result         text,
    created_at     timestamptz  not null default now(),
    completed_at   timestamptz,
    -- Invariant 9. Idempotency is enforced here rather than in application code: a replayed request
    -- cannot become a second mutation even if two callers race, because the database will not hold
    -- two rows with the same key.
    constraint uq_financial_operation_key unique (operation_key)
);
create index idx_financial_operations_subject on financial_operations (subject_type, subject_id);

create table audit_events (
    id           uuid primary key,
    business_id  uuid        not null references businesses (id),
    operation_id uuid        not null references financial_operations (id),
    event_type   varchar(40) not null,
    detail       text        not null,
    recorded_at  timestamptz not null default now()
);
create index idx_audit_events_operation on audit_events (operation_id);

-- The audit trail is append-only. Mutation of immutable history is BLOCKED by the risk model, so it
-- is refused by the database and not merely left unimplemented in the service layer.
create or replace function refuse_audit_mutation() returns trigger as $$
begin
    raise exception 'audit_events is append-only';
end;
$$ language plpgsql;

create trigger audit_events_append_only
    before update or delete on audit_events
    for each row execute function refuse_audit_mutation();
