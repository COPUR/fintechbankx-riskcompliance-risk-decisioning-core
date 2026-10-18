-- Step 2 of the credit risk data split: copy the staged monolith rows into
-- sc_rsk_decisioning.legacy_credit_risk_assessment. Run by run-backfill.sh
-- against the RISK SERVICE database (db_rsk_decisioning_<env>) after Flyway.
-- Re-runnable until cutover: a row already copied (same assessment_id) is
-- updated from the monolith when anything in it changed there (workflow
-- columns status, approved_by, approval_date, override_reason,
-- override_approved_by, review dates, recalculated figures, updated_at,
-- version), and left untouched when nothing changed.
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
ON CONFLICT (assessment_id) DO UPDATE
   SET customer_id = EXCLUDED.customer_id,
       loan_id = EXCLUDED.loan_id,
       application_id = EXCLUDED.application_id,
       assessment_date = EXCLUDED.assessment_date,
       assessment_type = EXCLUDED.assessment_type,
       risk_score = EXCLUDED.risk_score,
       risk_category = EXCLUDED.risk_category,
       probability_of_default = EXCLUDED.probability_of_default,
       loss_given_default = EXCLUDED.loss_given_default,
       exposure_at_default = EXCLUDED.exposure_at_default,
       expected_loss = EXCLUDED.expected_loss,
       risk_factors = EXCLUDED.risk_factors,
       protective_factors = EXCLUDED.protective_factors,
       mitigation_measures = EXCLUDED.mitigation_measures,
       model_version = EXCLUDED.model_version,
       confidence_score = EXCLUDED.confidence_score,
       stress_test_results = EXCLUDED.stress_test_results,
       regulatory_capital_required = EXCLUDED.regulatory_capital_required,
       economic_scenario = EXCLUDED.economic_scenario,
       review_date = EXCLUDED.review_date,
       next_assessment_due = EXCLUDED.next_assessment_due,
       assessed_by = EXCLUDED.assessed_by,
       approved_by = EXCLUDED.approved_by,
       approval_date = EXCLUDED.approval_date,
       status = EXCLUDED.status,
       override_reason = EXCLUDED.override_reason,
       override_approved_by = EXCLUDED.override_approved_by,
       created_at = EXCLUDED.created_at,
       updated_at = EXCLUDED.updated_at,
       version = EXCLUDED.version
 WHERE (legacy_credit_risk_assessment.customer_id,
       legacy_credit_risk_assessment.loan_id,
       legacy_credit_risk_assessment.application_id,
       legacy_credit_risk_assessment.assessment_date,
       legacy_credit_risk_assessment.assessment_type,
       legacy_credit_risk_assessment.risk_score,
       legacy_credit_risk_assessment.risk_category,
       legacy_credit_risk_assessment.probability_of_default,
       legacy_credit_risk_assessment.loss_given_default,
       legacy_credit_risk_assessment.exposure_at_default,
       legacy_credit_risk_assessment.expected_loss,
       legacy_credit_risk_assessment.risk_factors,
       legacy_credit_risk_assessment.protective_factors,
       legacy_credit_risk_assessment.mitigation_measures,
       legacy_credit_risk_assessment.model_version,
       legacy_credit_risk_assessment.confidence_score,
       legacy_credit_risk_assessment.stress_test_results,
       legacy_credit_risk_assessment.regulatory_capital_required,
       legacy_credit_risk_assessment.economic_scenario,
       legacy_credit_risk_assessment.review_date,
       legacy_credit_risk_assessment.next_assessment_due,
       legacy_credit_risk_assessment.assessed_by,
       legacy_credit_risk_assessment.approved_by,
       legacy_credit_risk_assessment.approval_date,
       legacy_credit_risk_assessment.status,
       legacy_credit_risk_assessment.override_reason,
       legacy_credit_risk_assessment.override_approved_by,
       legacy_credit_risk_assessment.created_at,
       legacy_credit_risk_assessment.updated_at,
       legacy_credit_risk_assessment.version)
       IS DISTINCT FROM
       (EXCLUDED.customer_id,
       EXCLUDED.loan_id,
       EXCLUDED.application_id,
       EXCLUDED.assessment_date,
       EXCLUDED.assessment_type,
       EXCLUDED.risk_score,
       EXCLUDED.risk_category,
       EXCLUDED.probability_of_default,
       EXCLUDED.loss_given_default,
       EXCLUDED.exposure_at_default,
       EXCLUDED.expected_loss,
       EXCLUDED.risk_factors,
       EXCLUDED.protective_factors,
       EXCLUDED.mitigation_measures,
       EXCLUDED.model_version,
       EXCLUDED.confidence_score,
       EXCLUDED.stress_test_results,
       EXCLUDED.regulatory_capital_required,
       EXCLUDED.economic_scenario,
       EXCLUDED.review_date,
       EXCLUDED.next_assessment_due,
       EXCLUDED.assessed_by,
       EXCLUDED.approved_by,
       EXCLUDED.approval_date,
       EXCLUDED.status,
       EXCLUDED.override_reason,
       EXCLUDED.override_approved_by,
       EXCLUDED.created_at,
       EXCLUDED.updated_at,
       EXCLUDED.version);

COMMIT;
