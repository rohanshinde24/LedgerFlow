"""Measure LedgerFlow against a frozen, versioned dataset.

Every number this produces has a stated numerator and denominator, a sample size, and a per-case row
written to disk so any figure can be traced back to the cases that produced it. Nothing is estimated
and nothing is reported that was not observed in a run.

Two scoring rules matter and are applied everywhere:

Abstention is not error. A case the system declined to answer is counted as abstained, never as
wrong. Precision is therefore reported over answered cases only, and automation rate over all cases.
Conflating the two would make guessing look like accuracy.

Correctness is judged against the frozen label, not against the system's own confidence.
"""

from __future__ import annotations

import argparse
import json
import statistics
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import httpx

ABSTAINING_TRANSACTION_OUTCOMES = {"REQUEST_CONTEXT", "ESCALATE"}
NO_EXPECTED_INVOICE = "NONE"
LABELS_DIR = Path(__file__).resolve().parent / "labels"


# --------------------------------------------------------------------------------------------
# Scoring primitives
# --------------------------------------------------------------------------------------------

def score_transaction(finding: dict[str, Any], expected_code: str) -> str:
    """CORRECT, WRONG or ABSTAINED for one categorization."""
    if finding["outcome"] in ABSTAINING_TRANSACTION_OUTCOMES:
        return "ABSTAINED"
    return "CORRECT" if finding.get("chart_of_account_code") == expected_code else "WRONG"


def score_reconciliation(proposal: dict[str, Any], expected: str) -> str:
    """CORRECT, WRONG or ABSTAINED for one payment.

    A payment whose label is NONE has no matching invoice in the record, so escalating it is the
    correct answer rather than an abstention: there is nothing to abstain from.
    """
    proposed = sorted(allocation["invoice_number"] for allocation in proposal.get("allocations", []))
    escalated = proposal["decision"] == "ESCALATE"

    if expected == NO_EXPECTED_INVOICE:
        return "CORRECT" if escalated else "WRONG"
    if escalated:
        return "ABSTAINED"
    return "CORRECT" if proposed == sorted(expected.split(",")) else "WRONG"


def macro_f1(rows: list[dict[str, Any]]) -> dict[str, Any]:
    """Macro F1 over the account codes that appear as a true label.

    Macro rather than micro because the code distribution is heavily skewed: a micro average would
    be dominated by the few codes that occur constantly and would hide failures on rare ones.
    Abstentions are not predictions, so they are excluded from precision but still count against
    recall, which is what makes declining to answer visible here rather than free.
    """
    answered = [row for row in rows if row["score"] != "ABSTAINED"]
    labels = sorted({row["expected"] for row in rows})

    per_label = {}
    f1_values = []
    for label in labels:
        true_positive = sum(1 for row in answered if row["predicted"] == label and row["expected"] == label)
        false_positive = sum(1 for row in answered if row["predicted"] == label and row["expected"] != label)
        false_negative = sum(1 for row in rows if row["expected"] == label and row.get("predicted") != label)

        precision = true_positive / (true_positive + false_positive) if true_positive + false_positive else 0.0
        recall = true_positive / (true_positive + false_negative) if true_positive + false_negative else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {"support": sum(1 for row in rows if row["expected"] == label),
                            "precision": round(precision, 4), "recall": round(recall, 4),
                            "f1": round(f1, 4)}
        f1_values.append(f1)

    return {"macro_f1": round(statistics.fmean(f1_values), 4) if f1_values else None,
            "labels_in_support": len(labels), "per_label": per_label}


def percentiles(values: list[float]) -> dict[str, float | None]:
    if not values:
        return {"median_ms": None, "p95_ms": None, "n": 0}
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))
    return {"median_ms": round(statistics.median(ordered), 1),
            "p95_ms": round(ordered[index], 1), "n": len(ordered)}


# --------------------------------------------------------------------------------------------
# Baselines
# --------------------------------------------------------------------------------------------

def majority_class_baseline(development: dict[str, Any]) -> str:
    """The most common account code in the DEVELOPMENT set.

    Fitted on development data and applied unchanged to the held-out set. Choosing it from the
    evaluation labels would be fitting the baseline to the test set, which would flatter it.
    """
    counts = Counter(row["expected_coa_code"] for row in development["transactions"])
    return counts.most_common(1)[0][0]


def exact_amount_baseline(payment: dict[str, Any], invoices: list[dict[str, Any]]) -> list[str]:
    """Propose the single open invoice whose outstanding amount equals the payment, if exactly one.

    This is the obvious thing a person would write before reaching for anything cleverer, which is
    what makes it the right thing to compare against.
    """
    amount = payment["amount"]
    matches = [invoice for invoice in invoices
               if invoice["outstandingAmount"]["amount"] == amount]
    return [matches[0]["invoiceNumber"]] if len(matches) == 1 else []


# --------------------------------------------------------------------------------------------
# Execution
# --------------------------------------------------------------------------------------------

def run_transactions(client: httpx.Client, dataset: dict[str, Any], checkpoint: Path,
                     limit: int | None) -> list[dict[str, Any]]:
    done = {}
    if checkpoint.exists():
        for line in checkpoint.read_text().splitlines():
            if line.strip():
                row = json.loads(line)
                done[row["transaction_id"]] = row

    subjects = dataset["transactions"][:limit] if limit else dataset["transactions"]
    results = []
    with checkpoint.open("a") as sink:
        for index, subject in enumerate(subjects, start=1):
            if subject["transaction_id"] in done:
                results.append(done[subject["transaction_id"]])
                continue

            started = time.monotonic()
            try:
                response = client.post("/api/agent/transactions/investigate",
                                       json={"transaction_id": subject["transaction_id"]})
                response.raise_for_status()
                finding = response.json()
                error = None
            except Exception as exc:  # noqa: BLE001 - a failed case is a datum, not a crash
                finding, error = None, f"{type(exc).__name__}: {exc}"
            elapsed_ms = (time.monotonic() - started) * 1000

            row = {
                "transaction_id": subject["transaction_id"],
                "expected": subject["expected_coa_code"],
                "difficulty_tag": subject["difficulty_tag"],
                "latency_ms": round(elapsed_ms, 1),
                "error": error,
            }
            if finding is not None:
                row.update({
                    "outcome": finding["outcome"],
                    "predicted": finding.get("chart_of_account_code"),
                    "source": finding["source"],
                    "score": score_transaction(finding, subject["expected_coa_code"]),
                    "model_calls": finding.get("model_calls", 0),
                    "capability_calls": finding.get("capability_calls", 0),
                    "capability_sequence": finding.get("capability_sequence", []),
                    "correction_attempts": finding.get("correction_attempts", 0),
                    "rejected_reasons": finding.get("rejected_reasons", []),
                    "input_tokens": finding.get("input_tokens", 0),
                    "output_tokens": finding.get("output_tokens", 0),
                    "confidence": finding.get("confidence"),
                })
            else:
                row.update({"outcome": "ERROR", "predicted": None, "source": None, "score": "ERROR"})

            sink.write(json.dumps(row) + "\n")
            sink.flush()
            results.append(row)
            print(f"[txn {index}/{len(subjects)}] {row['score']:9} {row['outcome']:18} "
                  f"{row['latency_ms']:8.0f}ms  {subject['difficulty_tag'] or ''}", flush=True)
    return results


def run_payments(client: httpx.Client, core: httpx.Client, dataset: dict[str, Any],
                 checkpoint: Path, limit: int | None) -> list[dict[str, Any]]:
    done = {}
    if checkpoint.exists():
        for line in checkpoint.read_text().splitlines():
            if line.strip():
                row = json.loads(line)
                done[row["payment_id"]] = row

    invoices = core.get("/api/invoices", params={"businessId": dataset["business_id"],
                                                 "size": 200}).json()["items"]
    subjects = dataset["payments"][:limit] if limit else dataset["payments"]
    results = []
    with checkpoint.open("a") as sink:
        for index, subject in enumerate(subjects, start=1):
            if subject["payment_id"] in done:
                results.append(done[subject["payment_id"]])
                continue

            started = time.monotonic()
            try:
                response = client.post("/api/agent/reconciliation/investigate",
                                       json={"payment_id": subject["payment_id"]})
                response.raise_for_status()
                proposal = response.json()
                error = None
            except Exception as exc:  # noqa: BLE001
                proposal, error = None, f"{type(exc).__name__}: {exc}"
            elapsed_ms = (time.monotonic() - started) * 1000

            baseline_numbers = exact_amount_baseline(subject, invoices)
            baseline_score = ("CORRECT" if (sorted(baseline_numbers)
                                            == sorted(subject["expected_invoice_numbers"].split(",")))
                              else "WRONG") if baseline_numbers else (
                "CORRECT" if subject["expected_invoice_numbers"] == NO_EXPECTED_INVOICE else "ABSTAINED")

            row = {
                "payment_id": subject["payment_id"],
                "expected": subject["expected_invoice_numbers"],
                "difficulty_tag": subject["difficulty_tag"],
                "latency_ms": round(elapsed_ms, 1),
                "baseline_score": baseline_score,
                "already_matched": subject["already_matched"],
                "error": error,
            }
            if proposal is not None:
                row.update({
                    "decision": proposal["decision"],
                    "predicted": sorted(a["invoice_number"] for a in proposal.get("allocations", [])),
                    "source": proposal["source"],
                    "score": score_reconciliation(proposal, subject["expected_invoice_numbers"]),
                    "model_calls": proposal.get("model_calls", 0),
                    "capability_calls": proposal.get("capability_calls", 0),
                    "candidate_count": proposal.get("candidate_count", 0),
                })
            else:
                row.update({"decision": "ERROR", "predicted": None, "source": None, "score": "ERROR"})

            sink.write(json.dumps(row) + "\n")
            sink.flush()
            results.append(row)
            print(f"[pay {index}/{len(subjects)}] {row['score']:9} {row['decision']:10} "
                  f"{row['latency_ms']:8.0f}ms  {subject['difficulty_tag'] or ''}", flush=True)
    return results


# --------------------------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------------------------

def rate(numerator: int, denominator: int) -> float | None:
    return round(numerator / denominator, 4) if denominator else None


def summarise(transactions: list[dict[str, Any]], payments: list[dict[str, Any]],
              dataset: dict[str, Any], baseline_code: str) -> dict[str, Any]:
    scored = [row for row in transactions if row["score"] != "ERROR"]
    deterministic = [row for row in scored if row["source"] == "DETERMINISTIC"]
    agent = [row for row in scored if row["source"] == "AGENT"]
    answered = [row for row in scored if row["score"] != "ABSTAINED"]
    agent_answered = [row for row in agent if row["score"] != "ABSTAINED"]

    baseline_rows = [{"expected": row["expected"], "predicted": baseline_code,
                      "score": "CORRECT" if row["expected"] == baseline_code else "WRONG"}
                     for row in scored]

    successful_agent = [row for row in agent_answered if row["score"] == "CORRECT"]
    agent_input = sum(row.get("input_tokens", 0) for row in agent)
    agent_output = sum(row.get("output_tokens", 0) for row in agent)

    # Reconciliation is only meaningful for payments that are not already applied. A payment matched
    # at generation time leaves no open invoice to find, so the deterministic finder correctly returns
    # no candidates and escalation is the only defensible answer — while the label still names the
    # invoice it is already matched to. Scoring those would measure nothing and depress recall with
    # cases that are not in scope.
    applied = {row["payment_id"] for row in dataset["payments"] if row["already_matched"]}
    out_of_scope = [row for row in payments if row["payment_id"] in applied]
    payments_scored = [row for row in payments
                       if row["score"] != "ERROR" and row["payment_id"] not in applied]
    payment_answered = [row for row in payments_scored if row["score"] != "ABSTAINED"]
    requires_human = [row for row in payments_scored if row["expected"] == NO_EXPECTED_INVOICE]
    correctly_escalated = [row for row in requires_human if row["score"] == "CORRECT"]

    return {
        "dataset": {"seed": dataset["seed"], "role": dataset["role"],
                    "frozen_at": dataset["frozen_at"],
                    "transactions": len(dataset["transactions"]),
                    "payments": len(dataset["payments"])},

        "end_to_end_transaction_resolution": {
            "definition": "correct answers / all labelled uncategorized transactions",
            "numerator": sum(1 for row in scored if row["score"] == "CORRECT"),
            "denominator": len(scored),
            "result": rate(sum(1 for row in scored if row["score"] == "CORRECT"), len(scored)),
            "baseline_majority_class": {
                "predicts": baseline_code,
                "numerator": sum(1 for row in baseline_rows if row["score"] == "CORRECT"),
                "denominator": len(baseline_rows),
                "result": rate(sum(1 for row in baseline_rows if row["score"] == "CORRECT"),
                               len(baseline_rows)),
            },
        },

        "categorization_precision": {
            "definition": "correct / answered (abstentions excluded from both)",
            "overall": {"numerator": sum(1 for row in answered if row["score"] == "CORRECT"),
                        "denominator": len(answered),
                        "result": rate(sum(1 for row in answered if row["score"] == "CORRECT"),
                                       len(answered))},
            "deterministic_path": {
                "numerator": sum(1 for row in deterministic if row["score"] == "CORRECT"),
                "denominator": len([r for r in deterministic if r["score"] != "ABSTAINED"]),
                "result": rate(sum(1 for row in deterministic if row["score"] == "CORRECT"),
                               len([r for r in deterministic if r["score"] != "ABSTAINED"]))},
            "agent_path": {
                "numerator": sum(1 for row in agent_answered if row["score"] == "CORRECT"),
                "denominator": len(agent_answered),
                "result": rate(sum(1 for row in agent_answered if row["score"] == "CORRECT"),
                               len(agent_answered))},
        },

        "categorization_macro_f1": {
            "definition": "unweighted mean F1 across account codes present as a true label",
            "system": macro_f1(scored),
            "baseline_majority_class": macro_f1(baseline_rows),
        },

        "automation_rate": {
            "definition": "cases resolved without human intervention / all cases",
            "numerator": len(answered),
            "denominator": len(scored),
            "result": rate(len(answered), len(scored)),
            "deterministic_path_share": {
                "numerator": len(deterministic), "denominator": len(scored),
                "result": rate(len(deterministic), len(scored))},
            "agent_path_share": {
                "numerator": len(agent), "denominator": len(scored),
                "result": rate(len(agent), len(scored))},
            "agent_abstention_rate": {
                "numerator": len(agent) - len(agent_answered), "denominator": len(agent),
                "result": rate(len(agent) - len(agent_answered), len(agent))},
        },

        "reconciliation": {
            "definition": "exact allocation-set match against expected invoice numbers",
            "precision": {"numerator": sum(1 for row in payment_answered if row["score"] == "CORRECT"),
                          "denominator": len(payment_answered),
                          "result": rate(sum(1 for row in payment_answered if row["score"] == "CORRECT"),
                                         len(payment_answered))},
            "recall": {"numerator": sum(1 for row in payments_scored if row["score"] == "CORRECT"),
                       "denominator": len(payments_scored),
                       "result": rate(sum(1 for row in payments_scored if row["score"] == "CORRECT"),
                                      len(payments_scored))},
            "baseline_exact_amount_match": {
                "numerator": sum(1 for row in payments_scored if row["baseline_score"] == "CORRECT"),
                "denominator": len(payments_scored),
                "result": rate(sum(1 for row in payments_scored if row["baseline_score"] == "CORRECT"),
                               len(payments_scored))},
            "scope": {
                "labelled_payments": len(dataset["payments"]),
                "already_applied_excluded": len(out_of_scope),
                "in_scope_unapplied": len(payments_scored),
                "note": ("denominator is unapplied payments only; payments already reconciled at "
                         "dataset generation have no open invoice left to match")},
        },

        "escalation_recall": {
            "definition": ("cases the dataset constructed with no recoverable answer that the system "
                           "escalated / all such cases"),
            "payments": {"numerator": len(correctly_escalated), "denominator": len(requires_human),
                         "result": rate(len(correctly_escalated), len(requires_human))},
            "transactions": {
                "numerator": 0, "denominator": 0, "result": None,
                "note": ("every transaction in this dataset has a correct account code, so there is "
                         "no transaction that genuinely requires a human. Transaction escalation "
                         "recall is undefined here, and any transaction abstention is a missed "
                         "answer rather than a correct refusal.")},
        },

        "latency": {
            "definition": "wall-clock milliseconds per investigate call, client-side",
            "all_transactions": percentiles([row["latency_ms"] for row in scored]),
            "deterministic_path": percentiles([row["latency_ms"] for row in deterministic]),
            "agent_path": percentiles([row["latency_ms"] for row in agent]),
            "reconciliation": percentiles([row["latency_ms"] for row in payments_scored]),
        },

        "agent_iterations": {
            "definition": "capability (tool) calls per agent-path case",
            "median_capability_calls": (statistics.median([row["capability_calls"] for row in agent])
                                        if agent else None),
            "median_model_calls": (statistics.median([row["model_calls"] for row in agent])
                                   if agent else None),
            "n": len(agent),
            "precision_by_capability_call_count": precision_by_calls(agent),
        },

        "tokens_and_cost": {
            "definition": "provider-reported tokens over agent-path cases",
            "agent_cases": len(agent),
            "successfully_resolved_agent_cases": len(successful_agent),
            "total_input_tokens": agent_input,
            "total_output_tokens": agent_output,
            "input_tokens_per_resolved_case": (round(agent_input / len(successful_agent), 1)
                                               if successful_agent else None),
            "output_tokens_per_resolved_case": (round(agent_output / len(successful_agent), 1)
                                                if successful_agent else None),
            "usd_cost_per_resolved_case": 0.0,
            "cost_note": ("inference runs locally via Ollama, so marginal cost is zero by "
                          "construction. Tokens are the meaningful quantity, not dollars."),
        },

        "errors": {"transactions": sum(1 for row in transactions if row["score"] == "ERROR"),
                   "payments": sum(1 for row in payments if row["score"] == "ERROR")},

        "by_difficulty": by_difficulty(scored),
    }


def precision_by_calls(agent: list[dict[str, Any]]) -> dict[str, Any]:
    """Paired with the count, never reported alone. Call count is a cost, not an achievement."""
    buckets: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in agent:
        buckets[row["capability_calls"]].append(row)
    out = {}
    for count in sorted(buckets):
        rows = buckets[count]
        answered = [row for row in rows if row["score"] != "ABSTAINED"]
        out[str(count)] = {"n": len(rows), "answered": len(answered),
                           "correct": sum(1 for row in answered if row["score"] == "CORRECT"),
                           "precision": rate(sum(1 for row in answered if row["score"] == "CORRECT"),
                                             len(answered))}
    return out


def by_difficulty(rows: list[dict[str, Any]]) -> dict[str, Any]:
    buckets: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        buckets[row["difficulty_tag"] or "(untagged)"].append(row)
    return {tag: {"n": len(group),
                  "correct": sum(1 for row in group if row["score"] == "CORRECT"),
                  "wrong": sum(1 for row in group if row["score"] == "WRONG"),
                  "abstained": sum(1 for row in group if row["score"] == "ABSTAINED")}
            for tag, group in sorted(buckets.items())}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--agent-url", default="http://127.0.0.1:8010")
    parser.add_argument("--core-url", default="http://127.0.0.1:8080")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--skip-payments", action="store_true")
    parser.add_argument("--out", default=None)
    args = parser.parse_args()

    dataset = json.loads((LABELS_DIR / f"seed-{args.seed}.json").read_text())
    development = json.loads((LABELS_DIR / "seed-20250101.json").read_text())
    baseline_code = majority_class_baseline(development)

    results_dir = Path(__file__).resolve().parent / "results"
    results_dir.mkdir(parents=True, exist_ok=True)
    out = Path(args.out) if args.out else results_dir / f"seed-{args.seed}-summary.json"

    agent_client = httpx.Client(base_url=args.agent_url, timeout=300.0)
    core_client = httpx.Client(base_url=args.core_url, timeout=60.0)
    try:
        transactions = run_transactions(agent_client, dataset,
                                        results_dir / f"seed-{args.seed}-transactions.jsonl",
                                        args.limit)
        payments = ([] if args.skip_payments else
                    run_payments(agent_client, core_client, dataset,
                                 results_dir / f"seed-{args.seed}-payments.jsonl", args.limit))
    finally:
        agent_client.close()
        core_client.close()

    summary = summarise(transactions, payments, dataset, baseline_code)
    out.write_text(json.dumps(summary, indent=2))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
