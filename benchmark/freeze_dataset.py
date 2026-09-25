"""Freeze one generated dataset's ground truth to a versioned file.

The benchmark never reads labels from the live database. It reads a file written here, so a rerun
months later scores against the same answers even if the database has moved on, and so a change to
the generator cannot silently rewrite a published result.

Each seed is frozen separately. Seed 20250101 is the development set: its cases have been inspected
individually and have driven prompt and policy changes, so it cannot serve as an evaluation set.
Seed 20260601 is held out and must stay that way to mean anything.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

LABELS_DIR = Path(__file__).resolve().parent / "labels"

QUERY = """
select json_build_object(
  'seed', %(seed)s::bigint,
  'business_id', (select distinct business_id::text from ground_truth_labels where dataset_seed = %(seed)s),
  'transactions', coalesce((
    select json_agg(r) from (
      select json_build_object(
        'transaction_id', t.id::text,
        'expected_coa_code', g.label_value,
        'difficulty_tag', g.difficulty_tag,
        'description', t.description,
        'counterparty', t.counterparty_raw,
        'amount', t.amount::text,
        'booked_date', t.booked_date::text,
        'categorization_status', t.categorization_status
      ) as r
      from ground_truth_labels g
      join transactions t on t.id = g.subject_id
      where g.dataset_seed = %(seed)s
        and g.label_key = 'expected_coa_code'
        and t.categorization_status = 'UNCATEGORIZED'
      order by t.id
    ) x), '[]'::json),
  'payments', coalesce((
    select json_agg(r) from (
      select json_build_object(
        'payment_id', p.id::text,
        'expected_invoice_numbers', g.label_value,
        'difficulty_tag', g.difficulty_tag,
        'amount', p.amount::text,
        'reference', p.reference,
        'payer_name', p.payer_name_raw,
        'received_date', p.received_date::text,
        'already_matched', exists(
          select 1 from reconciliation_matches m where m.payment_id = p.id)
      ) as r
      from ground_truth_labels g
      join payments p on p.id = g.subject_id
      where g.dataset_seed = %(seed)s
        and g.label_key = 'expected_invoice_numbers'
      order by p.id
    ) x), '[]'::json)
)
"""


def export(seed: int, container: str) -> dict:
    sql = QUERY.replace("%(seed)s", str(seed))
    completed = subprocess.run(
        ["docker", "exec", container, "psql", "-U", "ledgerflow", "-d", "ledgerflow", "-t", "-A", "-c", sql],
        capture_output=True, text=True, check=True)
    return json.loads(completed.stdout.strip())


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed", type=int, required=True)
    parser.add_argument("--container", default="ledgerflow-postgres")
    parser.add_argument("--role", choices=["development", "held-out"], required=True)
    args = parser.parse_args()

    dataset = export(args.seed, args.container)
    dataset["role"] = args.role
    dataset["frozen_at"] = datetime.now(timezone.utc).isoformat()

    LABELS_DIR.mkdir(parents=True, exist_ok=True)
    path = LABELS_DIR / f"seed-{args.seed}.json"
    path.write_text(json.dumps(dataset, indent=2, sort_keys=True))

    unresolvable = sum(1 for payment in dataset["payments"]
                       if payment["expected_invoice_numbers"] == "NONE")
    unapplied = sum(1 for payment in dataset["payments"] if not payment["already_matched"])
    print(f"{path}")
    print(f"  role                      {dataset['role']}")
    print(f"  business                  {dataset['business_id']}")
    print(f"  uncategorized transactions {len(dataset['transactions'])}")
    print(f"  labelled payments          {len(dataset['payments'])}")
    print(f"  of which unresolvable      {unresolvable}")
    print(f"  of which unapplied         {unapplied}  (reconciliation scope)")


if __name__ == "__main__":
    main()
