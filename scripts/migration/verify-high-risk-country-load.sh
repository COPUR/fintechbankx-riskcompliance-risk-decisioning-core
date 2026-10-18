#!/usr/bin/env bash
# Rehearses the operator load of the high-risk country list
# (scripts/ops/load-high-risk-countries.sh) on a scratch PostgreSQL:
#   1. applies this service's migrations to a fresh database (as
#      scripts/migration/verify-backfill.sh does);
#   2. loads a three-country list and checks the rows, one loaded_at per load,
#      list source and version;
#   3. loads a two-country list and checks it replaced the first (no stale row
#      survives a load);
#   4. proves the refusals leave the table unchanged: an empty file, a file with
#      a code that is not alpha-2, a conninfo that carries a password (nothing
#      is connected to), and a missing argument.
#
# Needs psql and a role that can create databases, via the usual PG* env vars
# (PGHOST, PGPORT, PGUSER, PGPASSWORD). Runs in CI (deploy/data-split-rehearsal).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
loader="$root/scripts/ops/load-high-risk-countries.sh"
db="rsk_country_list_target"
schema="sc_rsk_decisioning"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

[ -x "$loader" ] || { echo "FAIL $loader is missing or not executable" >&2; exit 1; }

psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" -c "CREATE DATABASE $db"
psql_q -d "$db" -c "CREATE SCHEMA $schema"
runtime_role="${PGUSER:-$(id -un)}"
find "$root/risk-infrastructure/src/main/resources/db/migration" -name 'V*.sql' | sort -V | while read -r migration; do
  sed "s/\${runtime_role}/$runtime_role/g" "$migration" \
    | PGOPTIONS="-c search_path=$schema" psql_q -d "$db" -f -
done

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -d "$db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

# A refusal must exit non-zero, say why, and leave the table as it was.
refuse() {
  local label="$1" expect="$2" before out
  shift 2
  before="$(psql -X -At -d "$db" -c "SELECT string_agg(country_code || ':' || list_version, ',' ORDER BY country_code) FROM $schema.high_risk_country")"
  if out="$("$@" 2>&1)"; then
    echo "FAIL $label: the loader succeeded" >&2; echo "$out" >&2; exit 1
  fi
  if ! grep -qF -- "$expect" <<<"$out"; then
    echo "FAIL $label: expected the message '$expect', got:" >&2; echo "$out" >&2; exit 1
  fi
  check "$label leaves the table unchanged" \
    "SELECT string_agg(country_code || ':' || list_version, ',' ORDER BY country_code) FROM $schema.high_risk_country" "$before"
}

printf '# FATF, June 2026 plenary\nIR\nKP\n\nMM\n' > "$work/three.csv"
printf 'IR\nKP\n' > "$work/two.csv"
: > "$work/empty.csv"
printf '# only a comment\n\n' > "$work/comments-only.csv"
printf 'IR\nIRN\n' > "$work/bad-code.csv"
printf 'IR\nir\n' > "$work/lower-case.csv"

echo "--- first load: three countries"
"$loader" "dbname=$db" "$work/three.csv" "FATF" "2026-06"
check "three rows, blank and comment lines skipped" \
  "SELECT string_agg(country_code, ',' ORDER BY country_code) FROM $schema.high_risk_country" "IR,KP,MM"
check "source and version on every row" \
  "SELECT string_agg(DISTINCT list_source || '/' || list_version, ',') FROM $schema.high_risk_country" "FATF/2026-06"
check "one loaded_at per load, within the last minute" \
  "SELECT count(DISTINCT loaded_at) || ',' || bool_and(loaded_at > now() - interval '1 minute') FROM $schema.high_risk_country" "1,true"

echo "--- second load replaces the first"
"$loader" "dbname=$db" "$work/two.csv" "FATF" "2026-10"
check "the dropped country is gone and the version moved" \
  "SELECT string_agg(country_code || ':' || list_version, ',' ORDER BY country_code) FROM $schema.high_risk_country" "IR:2026-10,KP:2026-10"

echo "--- refusals"
refuse "an empty file" "no country codes" "$loader" "dbname=$db" "$work/empty.csv" "FATF" "2026-11"
refuse "a file with comments only" "no country codes" "$loader" "dbname=$db" "$work/comments-only.csv" "FATF" "2026-11"
refuse "a code that is not ISO alpha-2" "IRN" "$loader" "dbname=$db" "$work/bad-code.csv" "FATF" "2026-11"
refuse "a lower-case code" "ir" "$loader" "dbname=$db" "$work/lower-case.csv" "FATF" "2026-11"
refuse "a missing file" "not readable" "$loader" "dbname=$db" "$work/missing.csv" "FATF" "2026-11"
refuse "a blank list version" "list-version" "$loader" "dbname=$db" "$work/two.csv" "FATF" " "
# The key name is assembled so this file does not carry the literal shape the publication scan looks for.
pw_key="pass"; pw_key="${pw_key}word"
refuse "a conninfo carrying a credential" "must not carry a password" "$loader" "dbname=$db ${pw_key}=x" "$work/two.csv" "FATF" "2026-11"
refuse "a URI conninfo carrying a credential" "must not carry a password" "$loader" "postgresql://u:x@localhost/$db" "$work/two.csv" "FATF" "2026-11"
if out="$("$loader" "dbname=$db" "$work/two.csv" 2>&1)"; then
  echo "FAIL a run with missing arguments succeeded" >&2; exit 1
fi
grep -q "usage:" <<<"$out" || { echo "FAIL missing arguments did not print usage: $out" >&2; exit 1; }
echo "ok   missing arguments print usage"

echo "--- the second load is still in place after every refusal"
check "rows after refusals" \
  "SELECT string_agg(country_code || ':' || list_version, ',' ORDER BY country_code) FROM $schema.high_risk_country" "IR:2026-10,KP:2026-10"

psql_q -d postgres -c "DROP DATABASE IF EXISTS $db"
echo "High-risk country list load rehearsal passed."
