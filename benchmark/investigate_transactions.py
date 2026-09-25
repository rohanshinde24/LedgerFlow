"""Scores the transaction investigation against the hidden ground truth.

Ground truth is never served by the public API, so the labels are frozen into
`labels/uncategorized_transactions.json` by `freeze_labels.sh` and read from there.

Nothing here is aggregated into a single accuracy number. A finding produced without a model and a
finding produced after four capability calls are different events and are reported as such.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import httpx

LABELS = Path(__file__).parent / "labels" / "uncategorized_transactions.json"

ABSTAINING = {"REQUEST_CONTEXT", "ESCALATE"}


def investigate(client: httpx.Client, transaction_id: str) -> dict[str, Any]:
    response = client.post("/api/agent/transactions/investigate",
                           json={"transaction_id": transaction_id})
    response.raise_for_status()
    return response.json()


def score(finding: dict[str, Any], label: dict[str, Any]) -> str:
    """CORRECT, WRONG or ABSTAINED. An abstention is not a wrong answer and is never counted as
    one; conflating the two would reward guessing."""
    if finding["outcome"] in ABSTAINING:
        return "ABSTAINED"
    expected = label["expected_coa_code"]
    if expected is None:
        return "ABSTAINED"
    return "CORRECT" if finding.get("chart_of_account_code") == expected else "WRONG"


def run(base_url: str, limit: int | None, checkpoint: Path,
        tags: set[str] | None = None, only: set[str] | None = None) -> dict[str, Any]:
    labels = json.loads(LABELS.read_text())
    if tags or only:
        labels = [label for label in labels
                  if (tags and label["difficulty_tag"] in tags)
                  or (only and label["transaction_id"] in only)]
    if limit:
        labels = labels[:limit]

    # A full sweep is tens of minutes of local inference. Each finding is appended as it lands so an
    # interrupted run resumes instead of starting over.
    results = [json.loads(line) for line in checkpoint.read_text().splitlines() if line.strip()] \
        if checkpoint.exists() else []
    done = {row["transaction_id"] for row in results}

    with httpx.Client(base_url=base_url, timeout=600.0) as client, \
            checkpoint.open("a") as journal:
        for index, label in enumerate(labels, start=1):
            if label["transaction_id"] in done:
                continue
            started = time.monotonic()
            try:
                finding = investigate(client, label["transaction_id"])
            except (httpx.HTTPError, json.JSONDecodeError) as error:
                print(f"[{index}/{len(labels)}] FAILED    {type(error).__name__}: {error}",
                      file=sys.stderr, flush=True)
                continue
            verdict = score(finding, label)
            results.append({
                "transaction_id": label["transaction_id"],
                "difficulty_tag": label["difficulty_tag"],
                "expected_coa_code": label["expected_coa_code"],
                "verdict": verdict,
                "outcome": finding["outcome"],
                "source": finding["source"],
                "chart_of_account_code": finding.get("chart_of_account_code"),
                "capability_calls": finding["capability_calls"],
                "capability_sequence": finding["capability_sequence"],
                "model_calls": finding["model_calls"],
                "correction_attempts": finding["correction_attempts"],
                "rejected_reasons": finding["rejected_reasons"],
                "seconds": round(time.monotonic() - started, 1),
            })
            journal.write(json.dumps(results[-1]) + "\n")
            journal.flush()
            print(f"[{index}/{len(labels)}] {verdict:9} {finding['outcome']:18} "
                  f"{finding['source']:13} {results[-1]['seconds']:>6}s",
                  file=sys.stderr, flush=True)
    return report(results)


def report(results: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(results)
    tier1 = [r for r in results if r["source"] == "DETERMINISTIC"]
    agent = [r for r in results if r["source"] == "AGENT"]

    def precision(rows: list[dict[str, Any]]) -> dict[str, Any]:
        answered = [r for r in rows if r["verdict"] != "ABSTAINED"]
        correct = [r for r in answered if r["verdict"] == "CORRECT"]
        return {
            "events": len(rows),
            "answered": len(answered),
            "correct": len(correct),
            "abstained": len(rows) - len(answered),
            "precision": round(len(correct) / len(answered), 3) if answered else None,
        }

    by_calls: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in agent:
        by_calls[row["capability_calls"]].append(row)

    return {
        "events": total,
        "model_avoidance_rate": round(len(tier1) / total, 3) if total else None,
        "agent_escalation_rate": (round(sum(r["outcome"] == "ESCALATE" for r in agent) / len(agent), 3)
                                  if agent else None),
        "deterministic": precision(tier1),
        "agent": precision(agent),
        "model_calls_per_event": round(sum(r["model_calls"] for r in results) / total, 2) if total else None,
        "correction_attempts": sum(r["correction_attempts"] for r in results),
        "capability_calls": {
            "median": statistics.median([r["capability_calls"] for r in agent]) if agent else None,
            # Paired on purpose: a high call count is not a success signal, and success falling off
            # as calls rise is the finding worth having.
            "success_rate_by_count": {
                str(count): precision(rows) for count, rows in sorted(by_calls.items())
            },
        },
        "outcomes": dict(Counter(r["outcome"] for r in results)),
        "by_difficulty": {
            tag or "UNTAGGED": precision([r for r in results if (r["difficulty_tag"] or "UNTAGGED") == (tag or "UNTAGGED")])
            for tag in sorted({r["difficulty_tag"] for r in results}, key=lambda t: t or "")
        },
        "results": results,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--agent-url", default="http://localhost:8010")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--out", type=Path, default=Path("benchmark-transactions.json"))
    parser.add_argument("--checkpoint", type=Path, default=Path("benchmark-transactions.jsonl"),
                        help="Append-only journal. Delete it to force a clean run.")
    parser.add_argument("--tag", action="append", dest="tags", default=None,
                        help="Restrict to a difficulty tag. Repeatable.")
    parser.add_argument("--only", action="append", dest="only", default=None,
                        help="Restrict to a transaction id. Repeatable.")
    args = parser.parse_args()

    summary = run(args.agent_url, args.limit, args.checkpoint,
                  set(args.tags) if args.tags else None,
                  set(args.only) if args.only else None)
    args.out.write_text(json.dumps(summary, indent=2))
    printable = {key: value for key, value in summary.items() if key != "results"}
    print(json.dumps(printable, indent=2))


if __name__ == "__main__":
    main()
