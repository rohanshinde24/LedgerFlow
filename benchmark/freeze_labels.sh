#!/usr/bin/env bash
# Freezes the hidden ground truth for uncategorized transactions into labels/.
# The labels are read straight from Postgres because the public API never serves them.
set -euo pipefail

container="${LEDGERFLOW_POSTGRES:-ledgerflow-postgres}"
out="$(dirname "$0")/labels/uncategorized_transactions.json"

docker exec "$container" psql -U ledgerflow -d ledgerflow -tAc "
select json_agg(row_to_json(r) order by r.transaction_id)
from (
  select t.id::text as transaction_id,
         t.external_ref,
         max(case when g.label_key='expected_coa_code' then g.label_value end) as expected_coa_code,
         max(case when g.label_key='duplicate_of_external_ref' then g.label_value end) as duplicate_of_external_ref,
         max(g.difficulty_tag) as difficulty_tag
  from transactions t
  join ground_truth_labels g on g.subject_id = t.id and g.subject_type='TRANSACTION'
  where t.categorization_status='UNCATEGORIZED'
  group by t.id, t.external_ref
) r;" | python3 -m json.tool > "$out"

echo "wrote $out"
