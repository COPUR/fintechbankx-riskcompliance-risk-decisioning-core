-- DBA bootstrap for svc-rsk-decisioning (runbook, "Database roles (DBA bootstrap)").
--
-- Run once per environment before the first deploy, and again whenever in
-- doubt (it is idempotent), with psql as the Aurora master user (or any role
-- with CREATEROLE and the GRANT OPTION on the database), connected to the
-- service's database db_rsk_decisioning_<env>:
--
--   psql "host=<writer endpoint> dbname=db_rsk_decisioning_<env> user=<master> sslmode=verify-full sslrootcert=<rds bundle>" \
--     -v ON_ERROR_STOP=1 -f db/bootstrap/roles.sql
--
-- psql variables (optional; the defaults are this service's role names):
--   owner_role    the schema / migration owner (Flyway in the migration Job,
--                 DB_MIGRATION_USERNAME); default risk_decisioning_owner
--   runtime_role  the role the service pods connect as (DB_USERNAME, Flyway
--                 placeholder runtime_role); default risk_decisioning_app
--
-- It creates both roles with LOGIN and no other attribute, and no password:
-- this file never holds one. Set each password out of band, then write the
-- same {"username","password"} to the Secrets Manager secret Terraform created
-- for it (<env>/risk-decisioning-service/db-migration, .../db-app), e.g. in
-- the same psql session (the client hashes it, nothing is logged in clear):
--   \password risk_decisioning_owner
--   \password risk_decisioning_app
--
-- Privileges it sets:
--   owner    CONNECT and CREATE on the database, so the migration Job creates
--            sc_rsk_decisioning (Flyway create-schemas) and owns it and every
--            object in it;
--   runtime  CONNECT on the database only, and no membership in the owner.
--            Everything else it gets from V11 (USAGE on the schema and the
--            table grants), run by the migration Job as the owner.
-- It does not create the schema (a non-superuser master can create a schema
-- for another role only after granting itself that role); it refuses to run
-- when sc_rsk_decisioning exists and is owned by any role but the owner.
--
-- scripts/ci/check-role-bootstrap.sh rehearses it (deploy/data-split-rehearsal).

\set ON_ERROR_STOP on

\if :{?owner_role}
\else
\set owner_role risk_decisioning_owner
\endif
\if :{?runtime_role}
\else
\set runtime_role risk_decisioning_app
\endif

SELECT :'owner_role' = :'runtime_role' AS same_role, current_database() AS database_name \gset
\if :same_role
DO $$ BEGIN RAISE EXCEPTION 'the runtime role must differ from the owner role: the service pods would own the schema'; END $$;
\endif

-- Roles: created when missing, attributes enforced on every run.
SELECT format('CREATE ROLE %I', r) FROM unnest(ARRAY[:'owner_role', :'runtime_role']) AS r
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = r) \gexec
ALTER ROLE :"owner_role" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
ALTER ROLE :"runtime_role" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

-- The runtime role must not act as the owner: drop a direct membership,
-- refuse an indirect one (it would have to be untangled by hand).
SELECT format('REVOKE %I FROM %I', :'owner_role', :'runtime_role')
 WHERE EXISTS (SELECT FROM pg_auth_members
                WHERE roleid = to_regrole(:'owner_role') AND member = to_regrole(:'runtime_role')) \gexec
SELECT pg_has_role(:'runtime_role', :'owner_role', 'MEMBER') AS runtime_is_owner \gset
\if :runtime_is_owner
DO $$ BEGIN RAISE EXCEPTION 'the runtime role is still a member of the owner role through another role'; END $$;
\endif

-- Database privileges.
GRANT CONNECT, CREATE ON DATABASE :"database_name" TO :"owner_role";
GRANT CONNECT ON DATABASE :"database_name" TO :"runtime_role";
REVOKE CREATE ON DATABASE :"database_name" FROM :"runtime_role";

-- The schema, once the migration Job created it, belongs to the owner.
SELECT coalesce((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'sc_rsk_decisioning'),
                :'owner_role') AS schema_owner,
       coalesce((SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = 'sc_rsk_decisioning'),
                :'owner_role') <> :'owner_role' AS schema_foreign \gset
\if :schema_foreign
SELECT format('DO $x$ BEGIN RAISE EXCEPTION %L; END $x$',
              format('schema sc_rsk_decisioning is owned by %s, not by the owner role %s: fix the ownership by hand',
                     :'schema_owner', :'owner_role')) \gexec
\endif
