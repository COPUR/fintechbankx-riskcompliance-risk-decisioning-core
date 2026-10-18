-- Credit risk assessments of loans and applications, moved from the
-- monolith's V15__Create_risk_assessments_table.sql (public.risk_assessments)
-- by db/backfill/run-backfill.sh. Kept for audit and model history; nothing
-- in this service writes them. Differences from the monolith table:
--   * no foreign keys to customers, loans or loan_applications: those belong
--     to svc-cus-profile-kyc and svc-ln-loan-lifecycle, ids are kept as text;
--   * expected_loss is copied as stored, not recomputed;
--   * the "assessment date not in the future" check is dropped: it compared
--     with CURRENT_DATE, which would make a historic copy re-check the clock.

CREATE TABLE legacy_credit_risk_assessment (
    assessment_id                VARCHAR(30)    PRIMARY KEY,
    customer_id                  VARCHAR(64)    NOT NULL,
    loan_id                      VARCHAR(64),
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
    version                      INTEGER,

    CONSTRAINT ck_legacy_credit_risk_score CHECK (risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_legacy_credit_risk_pd CHECK (probability_of_default BETWEEN 0 AND 1),
    CONSTRAINT ck_legacy_credit_risk_lgd CHECK (loss_given_default BETWEEN 0 AND 1),
    CONSTRAINT ck_legacy_credit_risk_subject CHECK (loan_id IS NOT NULL OR application_id IS NOT NULL)
);

CREATE INDEX ix_legacy_credit_risk_customer ON legacy_credit_risk_assessment (customer_id);
CREATE INDEX ix_legacy_credit_risk_loan ON legacy_credit_risk_assessment (loan_id) WHERE loan_id IS NOT NULL;

COMMENT ON TABLE legacy_credit_risk_assessment IS 'Read-only credit risk history migrated from the monolith.';
