-- Step 3 of the credit risk data split, run by run-backfill.sh against the
-- RISK SERVICE database. Row 1 holds totals compared with the monolith; any
-- further row is a copied assessment whose expected loss does not match
-- EAD x PD x LGD (the monolith's generated-column formula).

\set ON_ERROR_STOP on

SELECT count(*)                                             AS assessments,
       coalesce(sum(exposure_at_default), 0)::numeric(19,2) AS exposure_total,
       coalesce(sum(expected_loss), 0)::numeric(19,2)       AS expected_loss_total
  FROM sc_rsk_decisioning.legacy_credit_risk_assessment;

SELECT assessment_id, expected_loss
  FROM sc_rsk_decisioning.legacy_credit_risk_assessment
 WHERE expected_loss IS DISTINCT FROM round(exposure_at_default * probability_of_default * loss_given_default, 2);
