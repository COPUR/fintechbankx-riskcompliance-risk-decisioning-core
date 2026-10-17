-- Step 3 of the credit risk data split, run by run-backfill.sh against the
-- RISK SERVICE database while backfill_stage still holds the monolith
-- snapshot. Row 1 holds totals compared with the monolith; any further row is
-- a break, labelled:
--   EXPECTED_LOSS|<id>  expected loss does not match EAD x PD x LGD (the
--                       monolith's generated-column formula);
--   MISSING|<id>        a monolith row that is not in the service;
--   DIFF|<id>           a copied row whose columns differ from the monolith
--                       (for example a status approved after the first run).

\set ON_ERROR_STOP on

SELECT count(*)                                             AS assessments,
       coalesce(sum(exposure_at_default), 0)::numeric(19,2) AS exposure_total,
       coalesce(sum(expected_loss), 0)::numeric(19,2)       AS expected_loss_total
  FROM sc_rsk_decisioning.legacy_credit_risk_assessment;

SELECT 'EXPECTED_LOSS|' || assessment_id
  FROM sc_rsk_decisioning.legacy_credit_risk_assessment
 WHERE expected_loss IS DISTINCT FROM round(exposure_at_default * probability_of_default * loss_given_default, 2)
UNION ALL
SELECT CASE WHEN t.assessment_id IS NULL THEN 'MISSING|' ELSE 'DIFF|' END || s.assessment_id
  FROM backfill_stage.risk_assessments s
  LEFT JOIN sc_rsk_decisioning.legacy_credit_risk_assessment t ON t.assessment_id = s.assessment_id
 WHERE t.assessment_id IS NULL
    OR (s.customer_id::text, s.loan_id, s.application_id, s.assessment_date, s.assessment_type, s.risk_score, s.risk_category, s.probability_of_default, s.loss_given_default, s.exposure_at_default, s.expected_loss, s.risk_factors, s.protective_factors, s.mitigation_measures, s.model_version, s.confidence_score, s.stress_test_results, s.regulatory_capital_required, s.economic_scenario, s.review_date, s.next_assessment_due, s.assessed_by, s.approved_by, s.approval_date, s.status, s.override_reason, s.override_approved_by, s.created_at, s.updated_at, s.version)
       IS DISTINCT FROM
       (t.customer_id, t.loan_id, t.application_id, t.assessment_date, t.assessment_type, t.risk_score, t.risk_category, t.probability_of_default, t.loss_given_default, t.exposure_at_default, t.expected_loss, t.risk_factors, t.protective_factors, t.mitigation_measures, t.model_version, t.confidence_score, t.stress_test_results, t.regulatory_capital_required, t.economic_scenario, t.review_date, t.next_assessment_due, t.assessed_by, t.approved_by, t.approval_date, t.status, t.override_reason, t.override_approved_by, t.created_at, t.updated_at, t.version)
 ORDER BY 1;
