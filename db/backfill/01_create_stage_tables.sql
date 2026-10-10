-- Step 1 of the credit risk data split: a staging table in the RISK SERVICE
-- database that receives a CSV copy of the monolith's public.risk_assessments
-- (enterprise-loan-management-system V15__Create_risk_assessments_table.sql).

\set ON_ERROR_STOP on

DROP SCHEMA IF EXISTS backfill_stage CASCADE;
CREATE SCHEMA backfill_stage;

CREATE TABLE backfill_stage.risk_assessments (
    assessment_id                VARCHAR(30)    PRIMARY KEY,
    customer_id                  BIGINT         NOT NULL,
    loan_id                      VARCHAR(36),
    application_id               VARCHAR(20),
    assessment_date              DATE           NOT NULL,
    assessment_type              VARCHAR(30)    NOT NULL,
    risk_score                   NUMERIC(5, 2)  NOT NULL,
    risk_category                VARCHAR(20)    NOT NULL,
    probability_of_default       NUMERIC(5, 4)  NOT NULL,
    loss_given_default           NUMERIC(5, 4)  NOT NULL,
    exposure_at_default          NUMERIC(15, 2) NOT NULL,
    expected_loss                NUMERIC(15, 2),
    risk_factors                 JSONB          NOT NULL,
    protective_factors           JSONB,
    mitigation_measures          JSONB,
    model_version                VARCHAR(20)    NOT NULL,
    confidence_score             NUMERIC(5, 2),
    stress_test_results          JSONB,
    regulatory_capital_required  NUMERIC(15, 2),
    economic_scenario            VARCHAR(50),
    review_date                  DATE,
    next_assessment_due          DATE,
    assessed_by                  VARCHAR(100)   NOT NULL,
    approved_by                  VARCHAR(100),
    approval_date                DATE,
    status                       VARCHAR(20),
    override_reason              TEXT,
    override_approved_by         VARCHAR(100),
    created_at                   TIMESTAMPTZ,
    updated_at                   TIMESTAMPTZ,
    version                      INTEGER
);
