# LedgerFlow

An agentic financial-operations platform for small-business bookkeeping, built on one constraint:

> **LLM reasoning is nondeterministic. Financial state changes are deterministic.**

The language model can investigate, propose and classify. It can never change financial state. Every
proposal it produces is re-validated against the ledger by deterministic code before anything is
written, and a proposal that fails validation goes to a human instead of being quietly repaired.

## Why it is built this way

Bookkeeping is mostly unambiguous and occasionally genuinely hard. A payment matching one open
invoice for the same amount needs no intelligence. A payment covering two invoices from a customer
whose name is spelled three different ways does. Routing both through the same nondeterministic
component is slower and more expensive on the easy cases, and it gives you no guarantee on the hard
ones.

LedgerFlow splits the two paths and measures the split.

## Architecture

```
                      ┌─────────────────────────────────────────┐
                      │  Tier 1: deterministic                  │
  transaction  ──────▶│  amount and date matching, invoice      │──▶ resolved
  or payment          │  combination search, counterparty       │    (no model call)
                      │  precedent above threshold              │
                      └──────────────┬──────────────────────────┘
                                     │ unresolved
                                     ▼
                      ┌─────────────────────────────────────────┐
                      │  Tier 2: agent investigation            │
                      │  read-only typed capabilities,          │
                      │  evidence gathering, proposes an outcome│
                      └──────────────┬──────────────────────────┘
                                     │ proposal
                                     ▼
                      ┌─────────────────────────────────────────┐
                      │  Deterministic re-validation            │
                      │  re-reads evidence from the core and    │
                      │  re-derives the arithmetic itself       │
                      └──────┬───────────────────────┬──────────┘
                             │ accepted              │ rejected
                             ▼                       ▼
                      idempotent write        Tier 3: human review
```

The **financial core** (Java 21, Spring Boot, PostgreSQL) owns all financial state and every
accounting invariant. It is the only component that can write. Deterministic reconciliation generates
mathematically valid candidate combinations and asserts nothing about which one is correct.

The **agent service** (Python 3.12, FastAPI) owns reasoning and nothing else. Its client into the core
exposes only `get_*` and `list_*` methods, so the read-only guarantee is structural rather than a
matter of discipline. There is no write method for a model to reach.

Capabilities are bound per subject. While investigating one transaction, the agent's tools take no
identifier argument. The transaction is fixed when the tool set is constructed, so the model cannot
point the investigation at a different record.

### Categorization is investigation, not classification

The agent starts with the transaction and nothing else. It has to earn context by calling
capabilities, such as counterparty history, offsetting entries in sibling accounts, or similar
charges, and each result decides what it looks at next. The prompt never lists plausible categories,
because listing them turns the problem back into classification against a menu.

Abstention is a first-class outcome. The agent can ask the owner for one specific missing fact, or
escalate outright, and both count as correct behaviour rather than failure. In bookkeeping a confident
wrong answer costs more than a question.

### Validation re-derives instead of trusting

Membership in a candidate set is a claim made by a database query. Whether the money actually balances
is arithmetic, so the validator recomputes it:

* an internal transfer must book to an `ASSET` account, never revenue or expense
* the named counterpart must balance the subject exactly, in the same currency
* a duplicate must be the same charge, not merely a similar one
* a refund must reverse an expense account

Widening a query therefore cannot widen what counts as a valid transfer.

### Idempotent financial operations

The single write path carries a caller-supplied operation key. Claiming that key, applying the change
and writing the audit event all happen in one transaction, so there is no state where the key is
claimed but the change did not land. A crash rolls back the claim along with everything else.

The key is claimed before the subject is examined. Concurrent callers are separated by a unique
constraint rather than by whatever each of them managed to read first, and the loser of that race is
a duplicate request that replays the winner's result. A different key reaching an already-settled
subject is not a retry and is refused. Idempotency covers repeats of one request, not rewriting
history.

The audit trail is append-only, enforced by a database trigger that refuses updates and deletes, so
the guarantee holds against any writer and not only against callers that go through the ORM.

## Results

Two frozen, versioned datasets generated from independent seeds. The development set's cases were
inspected one by one and drove prompt and policy changes, so it can only report development figures.
The held-out set was measured once. Baselines are fitted on development labels and applied unchanged.
Abstention is scored separately from error throughout, and precision covers answered cases only.

| Metric | Held-out | n | Baseline |
|---|---|---|---|
| End-to-end transaction resolution | **94.5%** (69/73) | 73 | 20.5% majority class |
| Categorization precision, deterministic path | **100%** (44/44) | 44 | n/a |
| Categorization precision, agent path | 89.3% (25/28) | 28 | n/a |
| Macro F1 across 12 account codes | **0.909** | 73 | 0.028 |
| Automation rate, no human intervention | 98.6% (72/73) | 73 | n/a |
| Resolved deterministically, no model call | **60.3%** (44/73) | 73 | n/a |
| Reconciliation precision and recall | 100% and 100% (18/18) | 18 | 11.1% exact amount |

Reconciliation covers unapplied payments only. A payment that was already matched when the dataset
was generated leaves no open invoice to find.

### Reliability

Seven failure modes injected around the only code path that mutates financial state, each verified by
reading the database afterwards: duplicate request, concurrent duplicate delivery from eight racing
callers, retry after a lost response, process restart, transient dependency failure, a distinct
operation key reaching a settled subject, and attempted audit-trail tampering.

No scenario produced a duplicate financial mutation, and every scenario recovered on retry.

### The most useful finding

Precision against the number of capability calls the agent made, held-out:

| capability calls | n | answered | correct | precision |
|---|---|---|---|---|
| 1 | 8 | 8 | 8 | 1.000 |
| 2 | 16 | 16 | 16 | 1.000 |
| 4 | 4 | 3 | 0 | **0.000** |
| 6 | 1 | 1 | 1 | 1.000 |

Every wrong answer happened at four capability calls. Long investigations do not find more, they
wander past decisive evidence and then go wrong. The fix is better stopping behaviour rather than a
higher iteration ceiling, and that work is still open.

Tool-call count is recorded as a cost paired with a success rate, never as a goal. A high
model-avoidance rate is the point of the design, not a gap in it.

### Limitations

The held-out set scored higher than the development set. That is expected, since development holds the
cases I inspected and iterated on, but it means the held-out figure may be optimistic against
genuinely novel data. Sampling is pinned to greedy decoding with a fixed seed, but full-sweep
reproducibility across repeated runs is unverified. Escalation recall rests on two cases per seed,
which is too few to lean on. Data is synthetic, from one business profile, with twelve account codes
in support. `AMBIGUOUS_MERCHANT` is the live failure mode at 0 of 4.

Risk classification, an approval workflow, and durable resumable workflow state are specified but not
built. The current financial operation is atomic rather than resumable.

## Stack

Java 21, Spring Boot, JPA, Flyway, PostgreSQL, Python 3.12, FastAPI, TypeScript, Next.js, JUnit 5,
Testcontainers, pytest.

Synthetic data only. No recurring infrastructure cost.
