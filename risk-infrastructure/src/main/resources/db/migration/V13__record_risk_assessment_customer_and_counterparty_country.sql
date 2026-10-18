-- POST /api/v2/risk/assess (rsk-policy-v4): risk resolves highRiskCountry and
-- velocityScore itself. The decision of record keeps what it resolved them
-- from: the customer (velocity is counted per customer over a trailing
-- window) and the counterparty's ISO alpha-2 country (NULL when the account
-- was neither a valid IBAN nor an internal account with a configured home
-- country; such a counterparty is decided as high risk). Both are NULL on the
-- caller-attested v1 decisions, so no default and no backfill.
--
-- attestation_source gains RISK_RESOLVED (V5 allowed CALLER_ATTESTED only).

ALTER TABLE risk_assessment ADD COLUMN customer_id VARCHAR(64);
ALTER TABLE risk_assessment ADD COLUMN counterparty_country VARCHAR(2);

ALTER TABLE risk_assessment ADD CONSTRAINT ck_risk_assessment_counterparty_country
    CHECK (counterparty_country IS NULL OR counterparty_country ~ '^[A-Z]{2}$');

ALTER TABLE risk_assessment DROP CONSTRAINT ck_risk_assessment_attestation_source;
ALTER TABLE risk_assessment ADD CONSTRAINT ck_risk_assessment_attestation_source
    CHECK (attestation_source IN ('CALLER_ATTESTED', 'RISK_RESOLVED'));

-- A risk-resolved decision always knows its customer: that is what its velocity was counted for.
ALTER TABLE risk_assessment ADD CONSTRAINT ck_risk_assessment_resolved_has_customer
    CHECK (attestation_source <> 'RISK_RESOLVED' OR customer_id IS NOT NULL);

-- Velocity: count of a customer's decisions in the trailing window.
CREATE INDEX ix_risk_assessment_customer_velocity ON risk_assessment (customer_id, assessed_at) WHERE customer_id IS NOT NULL;

COMMENT ON COLUMN risk_assessment.customer_id IS 'Customer the transaction belongs to (v2); NULL on caller-attested decisions.';
COMMENT ON COLUMN risk_assessment.counterparty_country IS 'ISO 3166-1 alpha-2 country risk resolved from the counterparty account (v2); NULL when unresolved or caller-attested.';
