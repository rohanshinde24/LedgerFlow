# LedgerFlow — Implementation Specification

Canonical spec. Updated only when the design of an affected area changes.

## 1. Purpose

Agentic financial-operations platform for small businesses. Ingests synthetic small-business
financial data and helps resolve routine bookkeeping work: categorization, invoice/payment
reconciliation, anomaly detection, and financial investigation.

Central constraint: **LLM reasoning is nondeterministic; financial state changes are deterministic.**
The agent may investigate, choose tools, classify ambiguity, and propose actions. All mutations pass
through typed deterministic capabilities enforcing validation, risk policy, approval, invariants,
audit, and idempotency.

Out of scope: trading, crypto, lending decisions, tax advice, real payment rails, real bank credentials.

## 2. Repository layout

```
financial-core/    Java 21 + Spring Boot. Owns all financial state and invariants.
agent-service/     Python 3.12+ + FastAPI. Owns LLM reasoning and typed capability calls.  (P1)
web/               TypeScript + Next.js dashboard.
benchmark/         Evaluation harness, frozen datasets, per-case results.                    (P3)
infra/             Docker Compose, CI.
SPEC.md, HANDOFF.md
```

## 3. Phases

| Phase | Scope | Acceptance |
|---|---|---|
| P0 | Deterministic financial system: schema, entities, REST reads, deterministic reconciliation candidates, reporting, synthetic dataset + hidden ground truth, dashboard, tests | Works fully without any LLM |
| P1 | Agent service, model abstraction, structured outputs, typed read-only capabilities, categorization/reconciliation proposals, ambiguity escalation | Agent operates only through typed capabilities; cannot touch storage |
| P2 | Financial operations, idempotency, audit, fault injection (risk classification and approval workflow not built) | Approved mutations correct under retries; unauthorized mutations rejected |
| P3 | Frozen dataset, ground-truth splits, benchmark runner, scoring, baselines, machine-readable report | One command reproduces final results |
| P4 | QuickBooks sandbox, trace UI, OTel, CI/CD, deploy, README | Only after P0–P3 stable |

## 4. Money

- Single currency per business (ISO-4217, stored on the business and on each money-bearing row).
- Persistence: `numeric(19,4)`. Never binary floating point.
- Domain/API: `Money` value type (`BigDecimal` + `Currency`). Arithmetic across currencies throws.
- Rounding: `HALF_EVEN` to the currency's default fraction digits.

## 5. Domain model (P0)

- `Business` — tenant boundary. Every financial row carries `business_id`.
- `Account` — bank/credit account (`CHECKING`, `SAVINGS`, `CREDIT_CARD`).
- `ChartOfAccountEntry` — code + name + `AccountCategory` (`ASSET`/`LIABILITY`/`EQUITY`/`REVENUE`/`EXPENSE`).
- `Customer`, `Vendor` — counterparties. Vendor may carry a default chart-of-account entry.
- `Transaction` — bank line. Signed `amount` relative to the account (negative = money out).
  Carries `categorization_status` (`UNCATEGORIZED`/`CATEGORIZED`/`NEEDS_REVIEW`) and
  `categorization_source` (`IMPORT`/`RULE`/`AGENT`/`USER`).
- `Invoice` + `InvoiceLine` — receivable. `status`: `DRAFT`/`OPEN`/`PARTIALLY_PAID`/`PAID`/`VOID`.
- `Payment` — customer money received, optionally linked to a bank `Transaction`.
- `ReconciliationMatch` — payment↔invoice allocation with `amount_applied`, `status`
  (`PROPOSED`/`CONFIRMED`/`REJECTED`), `method`, `confidence`.
- `GroundTruthLabel` — hidden evaluation truth. Never exposed by the public API.

P2 adds: `FinancialOperation`, `ApprovalRequest`, `AuditEvent`, `AgentRun`, `AgentEvent`.
P3 adds: `EvaluationCase`, `EvaluationRun`.

## 6. Accounting invariants (enforced in Java, never by the LLM)

1. Money is fixed-precision; a business's rows share its currency.
2. `invoice.amount_paid` = Σ `amount_applied` of that invoice's `CONFIRMED` matches, recomputed
   inside the mutating transaction. Never drifts.
3. Σ `amount_applied` of confirmed matches ≤ `payment.amount` (no over-application of a payment).
4. Σ `amount_applied` of confirmed matches ≤ `invoice.total_amount` (no over-payment of an invoice).
5. `amount_applied > 0`; at most one match row per (payment, invoice).
6. Invoice status is derived from `amount_paid` vs `total_amount`, not set independently.
7. A payment and the invoice it settles must belong to the same business.
8. Internal transfers are not revenue or expense without corroborating evidence.
9. Financial mutations are atomic and idempotent on a stable operation id (P2).

## 7. Deterministic reconciliation (P0)

Candidate generation runs before any model call:
1. Filter by business, open/partially-paid status, and a date window around the payment.
2. Filter by amount plausibility: exact total, exact outstanding, or outstanding within tolerance.
3. Score candidates: amount closeness, customer identity/name similarity, reference containing the
   invoice number, date proximity.
4. Return a bounded ranked candidate set.

The agent is only invoked when the candidate set remains ambiguous. Candidate-set reduction is a
measured efficiency metric in P3. On the seeded dataset 14 of 16 unapplied payments settle here with
no model call at all.

## 7a. Execution tiers

LedgerFlow does not make every financial operation agentic. Each event takes one of three paths, and
which path it takes is itself a measured outcome.

```
Financial event
      │
      ▼
Tier 1 — Deterministic fast path
      │   obvious reconciliation, known-merchant categorization, exact invoice match,
      │   known recurring expense. No model. No inference cost.
      │
      ├── obvious ────────────────────────────► resolve
      │
      └── ambiguous / novel
                │
                ▼
          Tier 2 — Agent investigation
                │   novel counterparty, ambiguous transfer, multiple plausible invoices,
                │   possible duplicate, unexplained anomaly.
                │   Gathers evidence dynamically through read capabilities.
                │
         ┌──────┴──────┐
         ▼             ▼
     confident     insufficient
         │             │
         ▼             ▼
     proposal      Tier 3 — Human resolution
         │             │   agent supplies evidence plus a specific question
         │             │   or recommendation; human resolves or approves
         └──────┬──────┘
                ▼
      deterministic validation
                ▼
        controlled execution
```

The boundary between tiers 1 and 2 is drawn by task type, not by a wish for more tool calls:

- **Deterministic math belongs in Java.** Candidate pre-ranking, and combination generation for a
  payment that settles several invoices, are computed exactly. A model is never asked to solve
  subset-sum matching.
- **Contextual judgment belongs to the agent.** Which of several mathematically valid combinations
  is the real one, given customer, dates, references, memo, and historical behaviour.

Removing a deterministic layer to manufacture tool calls is an anti-goal. A high model-avoidance
rate is a feature.

## 7b. Reconciliation agent (P1)

`agent-service` exposes `POST /api/agent/reconciliation/investigate {payment_id}` and returns a
`ReconciliationProposal` — never a mutation.

1. Fetch the deterministic candidate set. If it is not ambiguous, return a `DETERMINISTIC` proposal
   and never construct a model client.
2. Otherwise brief the model with the payment, the ranked candidates, and what each signal means.
3. The model may call read capabilities (`get_invoice`, `list_customer_invoices`,
   `search_transactions`) and must finish with `propose_allocation` or `escalate`.
4. Every proposed allocation is re-validated deterministically against the candidate set: the
   invoice must be in the set, amounts must be positive, no allocation may exceed the invoice's
   outstanding amount, and the total may not exceed the payment's unapplied amount.
5. A failed validation escalates and records `rejected_reasons`. Proposals are never silently
   corrected — a repaired proposal would make measured agent accuracy meaningless.

Two structural guarantees, neither of which depends on the prompt:

- `FinancialCoreClient` exposes only `get_*`/`list_*`. There is no code path from the agent to a
  write, so no prompt can produce one.
- The capability registry contains only reads, so a mutating tool call is not expressible.

Model access sits behind a `ModelClient` protocol with three implementations: Anthropic, Ollama
(local, the `$0` default), and a scripted client that makes the loop testable without a network.

Pre-ranking is deliberately retained here. Reconciliation is a tier-1-dominant task: the shortlist
is deterministic evidence, not a leaked answer, and it keeps most payments away from the model.

For a payment that plausibly settles several invoices, `InvoiceCombinationGenerator` enumerates the
subsets of open invoices whose outstanding balances sum *exactly* to the unapplied amount and returns
them as `exactCombinations`. Subset-sum is arithmetic, so it is settled in Java; the agent chooses
among the valid combinations on contextual evidence and never performs the arithmetic itself.

Two rules keep the enumeration honest. A combination of one invoice is never reported, since that is
already an ordinary candidate. And an invoice set too large to enumerate reports no combinations at
all rather than a truncated list, because a silently truncated list would look like proof that no
multi-invoice settlement exists.

The existence of any exact combination forces `ambiguous = true`. A multi-invoice settlement is a
rival explanation for the same money, so it is ambiguity in itself however well a single invoice
scores.

## 7c. Transaction investigation (P1)

This is where agentic depth belongs, because the path is not knowable upfront.

The task is deliberately **not** "what category is this transaction?" It is: *determine what this
transaction represents from available financial evidence, or abstain.* Outcomes:

```
CATEGORIZE          INTERNAL_TRANSFER    MATCH_REFUND    MATCH_PAYMENT
RECURRING_EXPENSE   DUPLICATE            REQUEST_CONTEXT  ESCALATE
```

Initial context is intentionally minimal — id, date, description, amount, account. No ranked
category list, because supplying one collapses the task back into classification. Everything else
must be earned through capabilities: counterparty history, matching transfers in sibling accounts,
nearby transactions, recurring-payment history, related invoices.

Each observation determines the next call. An unseen counterparty leads to a search for a balancing
transaction in another account; finding one changes the hypothesis to `INTERNAL_TRANSFER`; failing
to find one leads elsewhere. Deterministic validation then confirms the hypothesis independently —
for a transfer: amounts balance, both accounts belong to the business, timestamps are plausible.

Abstaining is a correct outcome. A counterparty with a genuinely mixed history and no corroborating
evidence should return `REQUEST_CONTEXT`, not a guess.

Capabilities are bound to the subject transaction by `TransactionCapabilityRegistry`, so no
capability exposes a `transaction_id` parameter and the model cannot redirect the investigation at
another record.

Tier 1 for this task is precedent: a counterparty categorized the same way at least three times and
never otherwise is a rule rather than a judgement call, and settles without constructing a model.
One prior sighting is an anecdote and does not qualify.

**Validation re-reads its own evidence.** `ValidationContext` is built from fresh reads of the core,
never from what the model reported about what it saw. A claimed balancing entry must appear in the
offsetting set; a claimed duplicate must appear in the similar set; a claimed code must exist in the
chart of accounts and carry a category consistent with the direction of the money.

**Correction is bounded and counted.** A rejected finding is handed back once with its reasons, so
the model can retrieve what it missed. Nothing is patched on its behalf, the retry is validated
against evidence read afresh, and `correction_attempts` is recorded on the finding so a retry cannot
quietly flatter the accuracy figures. A second rejection escalates.

**An unreachable model is a routing decision, not an outage.** A timeout, refusal, or dead endpoint
raises `ModelUnavailable`, which both investigators translate into a tier-3 escalation — the same
answer they already give when reasoning runs out of room. The model timeout is therefore set tight
(180s) rather than generously: waiting longer buys an answer nobody is still waiting for, and the
human fallback already exists.

**Derived beats guessed.** The account code for an `INTERNAL_TRANSFER` is not asked of the model at
all. Once the balancing entry is confirmed, the money demonstrably went to the account that entry
sits in, and the code is read off the chart of accounts. Anything a deterministic rule can settle is
removed from the model's remit rather than prompted for more carefully.

## 7d. Measurements

Reported per path, not aggregated into a single accuracy figure:

```
model avoidance rate            events resolved in tier 1 / all events
agent escalation rate           tier 3 outcomes / agent-invoked events
deterministic precision/recall  tier 1 only
ambiguous precision/recall      tier 2 only
model calls per financial event
capability calls per agent task, as a distribution
success rate by capability-call count
```

The last two are paired on purpose. A high capability-call count is not a success signal; it may
mean the agent wandered. Success falling off at 4+ calls is an engineering finding. Tool-call count
is an observation, never an objective.

An abstention is scored as an abstention, never as a wrong answer, and precision is reported over
answered events only. Counting abstentions as failures would reward guessing, which is the opposite
of what this design is for.

`benchmark/run_benchmark.py` runs a frozen dataset through the live system and emits the report.
Ground truth is never served by the public API, so `freeze_dataset.py` reads the labels straight
from Postgres into `benchmark/labels/seed-<seed>.json`. The harness reads that file and never the
live database, so a rerun scores against the same answers even after the database has moved on.

Every reported percentage carries its numerator, denominator and sample size. Per-case rows are
written to `benchmark/results/` so any figure can be traced to the cases behind it.

Two datasets exist and must not be confused. Seed 20250101 is the development set: its cases have
been inspected individually and have driven prompt and policy changes, so it can only report
development figures. Seed 20260601 is held out. Baselines are fitted on development labels and
applied unchanged to the held-out set; fitting a baseline on evaluation labels would flatter it.

Escalation recall is defined from how the generator built each case, not from what the system does.
A payment labelled `NONE` has no matching invoice in the record, so escalating it is the correct
answer. No equivalent exists for transactions — every transaction carries a correct account code —
so transaction escalation recall is undefined on this data, and a transaction abstention is a missed
answer rather than a correct refusal.

## 8. Synthetic dataset

Seeded, reproducible generator (`ledgerflow.dataset.seeds`, a list). Each seed produces its own
business with its own ground truth, so a held-out evaluation set coexists with the development set
and neither is regenerated when the other is. One small-business profile
(software/marketing consultancy). Emits accounts, chart of accounts, customers, vendors, invoices,
lines, payments, bank transactions, recurring subscriptions, payroll-like expenses, transfers, and
refunds, plus hidden `GroundTruthLabel` rows.

Injected difficult cases, each tagged with a `difficulty_tag`: duplicate transaction, duplicate
invoice, partial payment, payment covering multiple invoices, identical amounts across invoices,
customer name variation, late payment, internal transfer, ambiguous merchant, uncategorized
transaction, refund, subscription price increase, missing invoice, unmatched payment, unusual vendor.

## 9. HTTP API (P0)

Read-only unless noted. All list endpoints are paged and scoped by `businessId`.

```
GET /api/businesses
GET /api/accounts
GET /api/chart-of-accounts
GET /api/customers
GET /api/vendors
GET /api/transactions            filters: accountId, from, to, categorizationStatus, q
GET /api/transactions/{id}
GET /api/invoices                filters: customerId, status
GET /api/invoices/{id}
GET /api/payments                filters: status
GET /api/payments/{id}           carries businessId so the agent can scope follow-up reads
GET /api/reconciliation/candidates?paymentId=
GET /api/reports/expenses-by-category   filters: from, to
GET /api/reports/cash-flow              filters: from, to, granularity
GET /api/reports/receivables            filters: asOf
```

Money is serialized as `{amount, currency}` with `amount` a decimal **string**, so no client ever
parses a monetary value into a binary float. Aggregation stays server-side for the same reason.

Errors use a single `ApiError` shape `{timestamp, status, code, message, details[]}`.

## 9a. Financial operations (P2)

One write path exists: `POST /api/operations/categorize-transaction`. It carries a caller-supplied
`operationKey`, and that key is what makes a retry safe.

Claiming the key, applying the change and writing the audit event happen in a single transaction.
There is therefore no state in which a key is claimed but the change did not land, so a replay can
never observe a half-finished operation, and a crash rolls back the claim along with everything
else.

The key is claimed before the subject is examined. Concurrent callers are separated by the unique
constraint rather than by whatever each of them managed to read first: the loser of that race is a
duplicate request and replays the winner's result. Checking the subject's state ahead of the claim
would instead report a retry as a conflicting mutation, which is wrong.

A *different* key reaching an already-settled transaction is not a retry, and is refused. Idempotency
covers repeats of one request; it does not license rewriting history.

`audit_events` is append-only, enforced by a database trigger that refuses updates and deletes. The
guarantee holds against any writer, not only against callers that go through JPA.

Not built: risk classification, the approval workflow, and durable resumable workflow state. The
operation is atomic rather than resumable, so there is nothing to resume.

## 10. Risk model (P2, specified; classification and approval not built)

`READ` → free. `PROPOSE` → no state change beyond proposal rows. `MUTATE` → requires an approved
`ApprovalRequest` unless explicitly exempted. `BLOCKED` → rejected always (real payments, fund
transfers, audit deletion, mutation of immutable history).

## 11. Testing

- Domain unit tests (`Money`, invoice balance, match validation, candidate scoring).
- PostgreSQL integration tests via Testcontainers (constraints, invariants, repository queries).
- API tests through `MockMvc`.
- P2: idempotency and fault-injection tests. P3: benchmark scoring tests.

## 12. Constraints

$0 recurring cost, free tiers only. Synthetic data only. No credentials committed. Two internal
docs: `SPEC.md` and `HANDOFF.md`.
