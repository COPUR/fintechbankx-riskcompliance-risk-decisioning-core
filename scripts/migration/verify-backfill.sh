#!/usr/bin/env bash
# Rehearses the monolith -> svc-rsk-decisioning credit risk data split end to
# end on a scratch PostgreSQL: builds a monolith-shaped source, applies this
# service's Flyway migrations to a separate database, runs
# db/backfill/run-backfill.sh twice (the second run proves it is idempotent)
# and checks the copied values.
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

echo "Backfill rehearsal passed."
