#!/usr/bin/env bash
# Rehearses the monolith -> svc-rsk-decisioning credit risk data split end to
# end on a scratch PostgreSQL: builds a monolith-shaped source, applies this
# service's Flyway migrations to a separate database, runs
# db/backfill/run-backfill.sh twice (the second run proves it is idempotent),
# approves an assessment in the monolith and runs it a third time (the change
# must reach the service and reconcile), checks the copied values, and proves
# the row-level reconciliation catches a row that differs from the monolith.
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
src_db="elms_risk_backfill_source"
dst_db="rsk_backfill_target"
schema="sc_rsk_decisioning"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

for db in "$src_db" "$dst_db"; do
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
done

psql_q -d "$src_db" -f "$root/db/backfill/test/monolith_fixture.sql"

psql_q -d "$dst_db" -c "CREATE SCHEMA $schema"
for migration in "$root"/risk-infrastructure/src/main/resources/db/migration/V*.sql; do
  PGOPTIONS="-c search_path=$schema" psql_q -d "$dst_db" -f "$migration"
done

for run in 1 2; do
  echo "--- backfill run $run"
  "$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db"
done

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$dst_db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

check "assessments copied once despite two runs" \
  "SELECT count(*) FROM $schema.legacy_credit_risk_assessment" "3"
check "customer id becomes the text id the customer service keeps" \
  "SELECT string_agg(DISTINCT customer_id, ',' ORDER BY customer_id) FROM $schema.legacy_credit_risk_assessment" "1,2"
check "expected loss carried as the monolith computed it" \
  "SELECT expected_loss FROM $schema.legacy_credit_risk_assessment WHERE assessment_id = 'RA-001'" "1012.50"
check "risk factors and approval kept" \
  "SELECT risk_factors->>'delinquencies' || ' ' || status FROM $schema.legacy_credit_risk_assessment WHERE assessment_id = 'RA-002'" "2 PENDING_APPROVAL"
check "transaction decisions untouched by the credit risk backfill" \
  "SELECT count(*) FROM $schema.risk_assessment" "0"

echo "--- RA-002 approved with an override in the monolith after the first runs"
psql_q -d "$src_db" -c "UPDATE risk_assessments
   SET status = 'APPROVED', approved_by = 'credit-head', approval_date = '2024-03-07',
       override_reason = 'Collateral added', override_approved_by = 'cro', updated_at = '2024-03-07T10:00:00Z',
       version = 1
 WHERE assessment_id = 'RA-002'"
"$root/db/backfill/run-backfill.sh" "dbname=$src_db" "dbname=$dst_db"

check "re-run carries the approval into the service" \
  "SELECT concat_ws('|', status, approved_by, approval_date, override_reason, override_approved_by, version) FROM $schema.legacy_credit_risk_assessment WHERE assessment_id = 'RA-002'" \
  "APPROVED|credit-head|2024-03-07|Collateral added|cro|1"
check "re-run leaves unchanged rows as they were" \
  "SELECT concat_ws('|', status, approved_by, version) FROM $schema.legacy_credit_risk_assessment WHERE assessment_id = 'RA-001'" \
  "APPROVED|credit-head|0"
check "still one row per assessment" \
  "SELECT count(*) FROM $schema.legacy_credit_risk_assessment" "3"

echo "--- reconciliation must catch a row that drifted from the monolith"
psql_q -d "$dst_db" -f "$root/db/backfill/01_create_stage_tables.sql"
psql -X -q -v ON_ERROR_STOP=1 -d "$src_db" -c "\copy (SELECT assessment_id, customer_id, loan_id, application_id, assessment_date, assessment_type, risk_score, risk_category, probability_of_default, loss_given_default, exposure_at_default, expected_loss, risk_factors, protective_factors, mitigation_measures, model_version, confidence_score, stress_test_results, regulatory_capital_required, economic_scenario, review_date, next_assessment_due, assessed_by, approved_by, approval_date, status, override_reason, override_approved_by, created_at, updated_at, version FROM risk_assessments) TO STDOUT WITH (FORMAT csv, HEADER true)" \
  | psql -X -q -v ON_ERROR_STOP=1 -d "$dst_db" -c "\copy backfill_stage.risk_assessments FROM STDIN WITH (FORMAT csv, HEADER true)"
psql_q -d "$dst_db" -c "UPDATE $schema.legacy_credit_risk_assessment SET status = 'REJECTED' WHERE assessment_id = 'RA-003'"
check "row-level diff reports the drifted row" \
  "$(sed -n '/^SELECT CASE WHEN/,/ORDER BY 1;/p' "$root/db/backfill/03_reconcile.sql")" "DIFF|RA-003"
psql_q -d "$dst_db" -c "DROP SCHEMA backfill_stage CASCADE"

echo "Backfill rehearsal passed."
