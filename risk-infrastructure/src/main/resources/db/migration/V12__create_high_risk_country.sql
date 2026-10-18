-- Risk owns the high-risk country fact (screening-facts plan, 2026-10-18): the
-- list of ISO 3166-1 alpha-2 codes POST /api/v2/risk/assess checks the
-- counterparty's country against. An operator loads it from the FATF list with
-- scripts/ops/load-high-risk-countries.sh (transactional replace, as the
-- schema owner); the service only reads it, and only while it is fresh: an
-- empty list or one whose loaded_at is older than RISK_COUNTRY_LIST_MAX_AGE
-- (P35D) makes the endpoint answer 503 RISK_SOURCE_UNAVAILABLE.
--
-- VARCHAR, not CHAR, for every text column: Hibernate validates the entity
-- against the schema at startup and expects varchar for String fields
-- (fault memory hibernate-validate-column-types).

CREATE TABLE high_risk_country (
    country_code  VARCHAR(2)   PRIMARY KEY,
    list_source   VARCHAR(128) NOT NULL,
    list_version  VARCHAR(64)  NOT NULL,
    loaded_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_high_risk_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_high_risk_country_source CHECK (length(trim(list_source)) > 0),
    CONSTRAINT ck_high_risk_country_version CHECK (length(trim(list_version)) > 0)
);

COMMENT ON TABLE high_risk_country IS 'Risk-owned high-risk country list (FATF), replaced whole by the operator load script; read-only for the service.';
COMMENT ON COLUMN high_risk_country.list_source IS 'Where the list comes from, e.g. FATF.';
COMMENT ON COLUMN high_risk_country.list_version IS 'Which publication, e.g. the plenary month 2026-06.';
COMMENT ON COLUMN high_risk_country.loaded_at IS 'When the operator loaded this list; the same instant on every row of one load.';

-- The runtime role reads the list and nothing else (V11 pattern: every migration that adds a table grants it explicitly).
DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the migration owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON TABLE high_risk_country FROM %I, PUBLIC', runtime_role);
    EXECUTE format('GRANT SELECT ON TABLE high_risk_country TO %I', runtime_role);
END
$$;
