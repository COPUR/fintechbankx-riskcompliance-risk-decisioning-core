#!/usr/bin/env bash
# Rehearses the DBA bootstrap (db/bootstrap/roles.sql) on a scratch PostgreSQL:
#   1. runs roles.sql twice on a fresh database (idempotence);
#   2. applies this service's migrations as the owner role, the way the chart's
#      migration Job does (the owner creates sc_rsk_decisioning, as Flyway's
#      create-schemas does, and V11 grants the runtime role);
#   3. runs roles.sql a third time on the migrated database (still idempotent,
#      grants unchanged);
#   4. checks the result: both roles LOGIN without passwords, superuser,
#      CREATEDB, CREATEROLE, REPLICATION or BYPASSRLS; the owner owns the
#      schema; the runtime role is not a member of the owner and has only the
#      V11 grants;
#   5. checks that roles.sql refuses an owner role equal to the runtime role
#      and a schema owned by another role.
#
# Needs psql and a superuser (CI: the data-split job's postgres service; locally
# sudo -u postgres) via the usual PG* env vars. Uses its own database and roles
# (rsk_ci_*), and drops them at the start and the end.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
roles_sql="$root/db/bootstrap/roles.sql"
db="rsk_role_bootstrap_ci"
owner="rsk_ci_owner"
runtime="rsk_ci_app"
schema="sc_rsk_decisioning"

psql_q() { psql -X -q -v ON_ERROR_STOP=1 "$@"; }

cleanup() {
  psql_q -d postgres -c "DROP DATABASE IF EXISTS $db" >/dev/null
  for role in "$runtime" "$owner" rsk_ci_other; do
    psql_q -d postgres -c "DROP ROLE IF EXISTS $role" >/dev/null
  done
}
cleanup
trap cleanup EXIT
psql_q -d postgres -c "CREATE DATABASE $db"

bootstrap() {
  psql_q -d "$db" -v owner_role="$owner" -v runtime_role="$runtime" -f "$roles_sql" "$@"
}

check() {
  local label="$1" sql="$2" expected="$3" actual
  actual="$(psql -X -At -v ON_ERROR_STOP=1 -d "$db" -c "$sql")"
  if [ "$actual" != "$expected" ]; then
    echo "FAIL $label: expected '$expected', got '$actual'" >&2
    exit 1
  fi
  echo "ok   $label"
}

echo "--- roles.sql run 1 and 2 (fresh database)"
bootstrap
bootstrap

echo "--- migrations as the owner (the migration Job)"
# Flyway creates the schema and its history table as the connecting owner before the first migration;
# V11 grants the runtime role SELECT on that table.
psql_q -d "$db" -c "SET ROLE $owner" -c "CREATE SCHEMA $schema" -c "CREATE TABLE $schema.flyway_schema_history (
  installed_rank integer PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL,
  type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum integer, installed_by varchar(100) NOT NULL,
  installed_on timestamp NOT NULL DEFAULT now(), execution_time integer NOT NULL, success boolean NOT NULL)"
find "$root/risk-infrastructure/src/main/resources/db/migration" -name 'V*.sql' | sort -V | while read -r migration; do
  sed "s/\${runtime_role}/$runtime/g" "$migration" \
    | PGOPTIONS="-c search_path=$schema -c role=$owner" psql_q -d "$db" -f -
done
grants="SELECT string_agg(table_name || ':' || privilege_type, ',' ORDER BY table_name, privilege_type)
  FROM information_schema.role_table_grants WHERE grantee = '$runtime' AND table_schema = '$schema'"
before="$(psql -X -At -d "$db" -c "$grants")"

echo "--- roles.sql run 3 (migrated database)"
bootstrap

check "both roles exist and log in, without a password, with no elevated attribute" \
  "SELECT string_agg(rolname || ':' || rolcanlogin || rolsuper || rolcreatedb || rolcreaterole || rolreplication || rolbypassrls
     || ':' || (rolpassword IS NULL), ',' ORDER BY rolname) FROM pg_authid WHERE rolname IN ('$owner', '$runtime')" \
  "$runtime:truefalsefalsefalsefalsefalse:true,$owner:truefalsefalsefalsefalsefalse:true"
check "the owner owns the schema" \
  "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = '$schema'" "$owner"
check "the owner owns every table in the schema" \
  "SELECT count(*) FROM pg_tables WHERE schemaname = '$schema' AND tableowner <> '$owner'" "0"
check "the owner may connect and create schemas in the database" \
  "SELECT has_database_privilege('$owner', '$db', 'CONNECT') AND has_database_privilege('$owner', '$db', 'CREATE')" "t"
check "the runtime role may connect but not create schemas" \
  "SELECT has_database_privilege('$runtime', '$db', 'CONNECT') || ',' || has_database_privilege('$runtime', '$db', 'CREATE')" "true,false"
check "the runtime role is not a member of the owner" \
  "SELECT pg_has_role('$runtime', '$owner', 'MEMBER')" "f"
check "the runtime role cannot create objects in the schema" \
  "SELECT has_schema_privilege('$runtime', '$schema', 'USAGE') || ',' || has_schema_privilege('$runtime', '$schema', 'CREATE')" "true,false"
check "the runtime role has exactly the V11 grants, unchanged by the third run" \
  "$grants" "$before"
check "V11 grants: decisions insert-only, legacy read-only, outbox worked, history read" \
  "$grants" \
  "flyway_schema_history:SELECT,legacy_credit_risk_assessment:SELECT,outbox_event:DELETE,outbox_event:INSERT,outbox_event:SELECT,outbox_event:UPDATE,risk_assessment:INSERT,risk_assessment:SELECT"

echo "--- roles.sql refuses what it cannot make safe"
if out="$(psql_q -d "$db" -v owner_role="$owner" -v runtime_role="$owner" -f "$roles_sql" 2>&1)"; then
  echo "FAIL roles.sql accepted the runtime role equal to the owner" >&2; exit 1
fi
grep -q "runtime role must differ from the owner role" <<<"$out"
echo "ok   refuses the runtime role equal to the owner"
psql_q -d postgres -c "CREATE ROLE rsk_ci_other"
psql_q -d "$db" -c "ALTER SCHEMA $schema OWNER TO rsk_ci_other"
if out="$(bootstrap 2>&1)"; then
  echo "FAIL roles.sql accepted a schema owned by another role" >&2; exit 1
fi
grep -q "is owned by rsk_ci_other, not by the owner role $owner" <<<"$out"
echo "ok   refuses a schema owned by another role"
psql_q -d "$db" -c "ALTER SCHEMA $schema OWNER TO $owner"
psql_q -d postgres -c "GRANT $owner TO $runtime"
bootstrap
check "a runtime role granted the owner is taken out of it" \
  "SELECT pg_has_role('$runtime', '$owner', 'MEMBER')" "f"
echo "role bootstrap rehearsal passed"
