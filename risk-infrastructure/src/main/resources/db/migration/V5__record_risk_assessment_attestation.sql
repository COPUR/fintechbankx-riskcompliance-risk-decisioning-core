-- Risk applies its thresholds to facts the caller states (highRiskCountry,
-- velocityScore); it verifies none of them yet. Each decision of record says
-- so: attestation_source CALLER_ATTESTED, and attested_by names who stated the
-- facts (the calling client's azp for SERVICE callers, the subject for staff).
-- Decisions stored before this migration were also caller-attested; who
-- attested them was not recorded, so their attested_by stays NULL.

ALTER TABLE risk_assessment ADD COLUMN attestation_source VARCHAR(32) NOT NULL DEFAULT 'CALLER_ATTESTED';
ALTER TABLE risk_assessment ADD COLUMN attested_by VARCHAR(255);

-- New rows must state their attestation; the default only backfilled existing rows.
ALTER TABLE risk_assessment ALTER COLUMN attestation_source DROP DEFAULT;
ALTER TABLE risk_assessment ADD CONSTRAINT ck_risk_assessment_attestation_source
    CHECK (attestation_source IN ('CALLER_ATTESTED'));

COMMENT ON COLUMN risk_assessment.attestation_source IS 'CALLER_ATTESTED: the risk facts were stated by the caller, not verified by risk.';
COMMENT ON COLUMN risk_assessment.attested_by IS 'Client id (azp) of a SERVICE caller or subject of a staff caller; NULL for rows decided before V5.';
