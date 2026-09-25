"""Merge the per-seed summaries and the fault-injection results into one report.

Reads only files produced by actual runs. If a run has not happened, its section is absent rather
than estimated.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results"
FAULTS = ROOT.parent / "financial-core" / "target" / "fault-injection-results.json"


def main() -> None:
    report: dict = {"generated_at": datetime.now(timezone.utc).isoformat(), "seeds": {}}

    for path in sorted(RESULTS.glob("seed-*-summary.json")):
        summary = json.loads(path.read_text())
        report["seeds"][str(summary["dataset"]["seed"])] = summary

    if FAULTS.exists():
        faults = json.loads(FAULTS.read_text())
        report["reliability"] = {
            "definition": "failures injected around the only code path that mutates financial state",
            "scenarios": faults["scenario_count"],
            "duplicate_financial_mutations": faults["duplicate_mutations_total"],
            "recovered": faults["recovered"],
            "recovery_rate": (round(faults["recovered"] / faults["scenario_count"], 4)
                              if faults["scenario_count"] else None),
            "request_attempts": sum(s["attempts"] for s in faults["scenarios"]),
            "per_scenario": faults["scenarios"],
            "reproduce": "mvn -f financial-core test -Dtest=FinancialOperationFaultInjectionTest",
        }
    else:
        report["reliability"] = {"note": "not run; no fault-injection results file present"}

    out = RESULTS / "benchmark-report.json"
    out.write_text(json.dumps(report, indent=2))
    print(f"wrote {out}")
    print(f"  seeds: {', '.join(report['seeds'])}")
    if "scenarios" in report["reliability"]:
        print(f"  reliability: {report['reliability']['scenarios']} scenarios, "
              f"{report['reliability']['duplicate_financial_mutations']} duplicate mutations")


if __name__ == "__main__":
    main()
