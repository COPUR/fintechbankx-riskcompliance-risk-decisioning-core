-- Each decision of record names the policy rule set that made it, as
-- compliance_screening.rule_set_version does. rsk-policy-v1: amount
-- thresholds 10000/50000 in any currency (decisions stored before this
-- migration). rsk-policy-v2: thresholds per currency, USD only, other
-- currencies go to REVIEW with UNSUPPORTED_CURRENCY. The version is recorded,
-- not compared when a retry is matched.

ALTER TABLE risk_assessment ADD COLUMN rule_set_version VARCHAR(64) NOT NULL DEFAULT 'rsk-policy-v1';

-- New rows must state their rule set; the default only backfilled existing rows.
ALTER TABLE risk_assessment ALTER COLUMN rule_set_version DROP DEFAULT;

COMMENT ON COLUMN risk_assessment.rule_set_version IS 'Policy rule set that made the decision, e.g. rsk-policy-v2.';
