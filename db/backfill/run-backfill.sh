#!/usr/bin/env bash
# Copies credit risk assessments from the monolith database into
# svc-rsk-decisioning's own database and reconciles the two. Re-runnable.
#
#   db/backfill/run-backfill.sh <monolith-conninfo> <risk-service-conninfo>
#
# Example conninfo: "host=elms-db dbname=elms user=readonly sslmode=require".
# Passwords come from PGPASSWORD or ~/.pgpass, never from arguments.
set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "usage: $0 <monolith-conninfo> <risk-service-conninfo>" >&2
  exit 2
fi

source_db="$1"
target_db="$2"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

run() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

echo "Exporting risk assessments from the monolith (read-only snapshot)..."
run "$source_db" <<SQL
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\copy (SELECT assessment_id, customer_id, loan_id, application_id, assessment_date, assessment_type, risk_score, risk_category, probability_of_default, loss_given_default, exposure_at_default, expected_loss, risk_factors, protective_factors, mitigation_measures, model_version, confidence_score, stress_test_results, regulatory_capital_required, economic_scenario, review_date, next_assessment_due, assessed_by, approved_by, approval_date, status, override_reason, override_approved_by, created_at, updated_at, version FROM risk_assessments ORDER BY assessment_id) TO '$work/risk_assessments.csv' WITH (FORMAT csv, HEADER true)
COMMIT;
SQL

echo "Staging and transforming into sc_rsk_decisioning..."
run "$target_db" -f "$here/01_create_stage_tables.sql"
run "$target_db" <<SQL
\copy backfill_stage.risk_assessments FROM '$work/risk_assessments.csv' WITH (FORMAT csv, HEADER true)
SQL
run "$target_db" -f "$here/02_transform_into_risk_service.sql"

echo "Reconciling..."
source_figures="$(psql -X -At "$source_db" -c "
  SELECT count(*), coalesce(sum(exposure_at_default), 0)::numeric(19,2), coalesce(sum(expected_loss), 0)::numeric(19,2) FROM risk_assessments")"
reconcile="$(psql -X -At "$target_db" -f "$here/03_reconcile.sql")"
target_figures="$(echo "$reconcile" | sed -n '1p')"
invariant_breaks="$(echo "$reconcile" | sed -n '2,$p')"

echo "monolith     rows|exposure|expected loss: $source_figures"
echo "risk service rows|exposure|expected loss: $target_figures"
if [ "$source_figures" != "$target_figures" ]; then
  echo "RECONCILIATION FAILED: totals differ" >&2
  exit 1
fi
if [ -n "$invariant_breaks" ]; then
  echo "RECONCILIATION FAILED: assessments whose expected loss does not match EAD x PD x LGD:" >&2
  echo "$invariant_breaks" >&2
  exit 1
fi

run "$target_db" -c "DROP SCHEMA backfill_stage CASCADE"
echo "Backfill reconciled."
