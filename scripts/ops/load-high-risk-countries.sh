#!/usr/bin/env bash
# Loads the risk-owned high-risk country list (sc_rsk_decisioning.high_risk_country, V12) from a file of
# ISO 3166-1 alpha-2 codes, one per line (blank lines and lines starting with # are skipped), taken from the
# FATF publication the operator names. The whole list is replaced in one transaction, so the service never
# sees a half-loaded list, and every row of a load carries the same loaded_at (the transaction's start time).
#
# Usage: scripts/ops/load-high-risk-countries.sh <conninfo> <codes file> <list source> <list version>
#   conninfo      psql connection string or URI for the service database, as the schema owner (the runtime
#                 role may only read the table). It must not carry a password; use PGPASSFILE, PGSERVICE or
#                 the connection service file, so the credential never lands in a shell history or a job log.
#   codes file    e.g. fatf-2026-06.txt with one upper-case alpha-2 code per line.
#   list source   e.g. FATF
#   list version  e.g. 2026-06 (the plenary month)
#
# Refuses, and changes nothing: a conninfo with a password, an unreadable or empty file (no codes after
# comments), a line that is not an upper-case alpha-2 code, a blank source or version. Rehearsed by
# scripts/migration/verify-high-risk-country-load.sh (CI: deploy/data-split-rehearsal).
set -euo pipefail

usage() {
  echo "usage: $(basename "$0") <conninfo> <codes file> <list source> <list version>" >&2
  exit 2
}

[ "$#" -eq 4 ] || usage
conninfo="$1"
codes_file="$2"
list_source="$3"
list_version="$4"
schema="sc_rsk_decisioning"

# A credential in the connection string would be visible in ps, shell history and job logs.
if grep -qiE '(^|[[:space:]])pass(word)?[[:space:]]*=' <<<"$conninfo" || grep -qE '^[a-z]+://[^/@]*:[^/@]*@' <<<"$conninfo"; then
  echo "refused: the conninfo must not carry a password; use PGPASSFILE or a connection service" >&2
  exit 1
fi
[ -r "$codes_file" ] || { echo "refused: codes file '$codes_file' is not readable" >&2; exit 1; }
[ -n "${list_source//[[:space:]]/}" ] || { echo "refused: list-source must not be blank" >&2; exit 1; }
[ -n "${list_version//[[:space:]]/}" ] || { echo "refused: list-version must not be blank" >&2; exit 1; }
if [ "${#list_source}" -gt 128 ] || [ "${#list_version}" -gt 64 ]; then
  echo "refused: list-source is at most 128 characters and list-version at most 64" >&2
  exit 1
fi

codes=()
line_no=0
while IFS= read -r line || [ -n "$line" ]; do
  line_no=$((line_no + 1))
  code="${line%%#*}"
  code="${code//[[:space:]]/}"
  [ -n "$code" ] || continue
  if ! [[ "$code" =~ ^[A-Z]{2}$ ]]; then
    echo "refused: line $line_no '$code' is not an upper-case ISO 3166-1 alpha-2 code" >&2
    exit 1
  fi
  codes+=("$code")
done < "$codes_file"
[ "${#codes[@]}" -gt 0 ] || { echo "refused: '$codes_file' holds no country codes" >&2; exit 1; }

# One transaction: delete, insert every code with the same loaded_at (now() is the transaction start), report.
{
  echo "BEGIN;"
  echo "LOCK TABLE ${schema}.high_risk_country IN SHARE ROW EXCLUSIVE MODE;"
  echo "DELETE FROM ${schema}.high_risk_country;"
  for code in "${codes[@]}"; do
    printf "INSERT INTO %s.high_risk_country (country_code, list_source, list_version, loaded_at) VALUES ('%s', :'list_source', :'list_version', now());\n" \
      "$schema" "$code"
  done
  echo "SELECT count(*) AS loaded, min(loaded_at) AS loaded_at, :'list_source' AS list_source, :'list_version' AS list_version FROM ${schema}.high_risk_country;"
  echo "COMMIT;"
} | psql -X -q -v ON_ERROR_STOP=1 -v list_source="$list_source" -v list_version="$list_version" "$conninfo" -f -

echo "Loaded ${#codes[@]} high-risk countries (${list_source} ${list_version}) into ${schema}.high_risk_country."
