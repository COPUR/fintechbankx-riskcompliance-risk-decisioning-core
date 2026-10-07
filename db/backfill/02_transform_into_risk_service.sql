-- Step 2 of the credit risk data split: copy the staged monolith rows into
-- sc_rsk_decisioning.legacy_credit_risk_assessment. Run by run-backfill.sh
-- against the RISK SERVICE database (db_rsk_decisioning_<env>) after Flyway.
-- Idempotent: rows already copied (same assessment_id) are skipped.
--
-- Mapping (see docs/migration/RUNBOOK-EXTRACT-rsk-decisioning.md): every column
-- is copied as is, except customer_id, which becomes text (the id
-- svc-cus-profile-kyc keeps), and expected_loss, copied as the monolith stored it.

\set ON_ERROR_STOP on

BEGIN;

INSERT INTO sc_rsk_decisioning.legacy_credit_risk_assessment (
    assessment_id, customer_id, loan_id, application_id, assessment_date, assessment_type, risk_score, risk_category, probability_of_default, loss_given_default, exposure_at_default, expected_loss, risk_factors, protective_factors, mitigation_measures, model_version, confidence_score, stress_test_results, regulatory_capital_required, economic_scenario, review_date, next_assessment_due, assessed_by, approved_by, approval_date, status, override_reason, override_approved_by, created_at, updated_at, version)
SELECT assessment_id, customer_id::text, loan_id, application_id, assessment_date, assessment_type, risk_score,
       risk_category, probability_of_default, loss_given_default, exposure_at_default, expected_loss, risk_factors,
       protective_factors, mitigation_measures, model_version, confidence_score, stress_test_results,
       regulatory_capital_required, economic_scenario, review_date, next_assessment_due, assessed_by, approved_by,
       approval_date, status, override_reason, override_approved_by, created_at, updated_at, version
  FROM backfill_stage.risk_assessments
ON CONFLICT (assessment_id) DO NOTHING;

COMMIT;
