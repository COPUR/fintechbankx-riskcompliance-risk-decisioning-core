-- Fixture with the column layout of the monolith's
-- V15__Create_risk_assessments_table.sql (foreign keys, date checks against
-- CURRENT_DATE and indexes left out; the generated expected_loss is kept).
CREATE TABLE risk_assessments (
    assessment_id VARCHAR(30) PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    loan_id VARCHAR(36),
    application_id VARCHAR(20),
    assessment_date DATE NOT NULL,
    assessment_type VARCHAR(30) NOT NULL,
    risk_score DECIMAL(5,2) NOT NULL,
    risk_category VARCHAR(20) NOT NULL,
    probability_of_default DECIMAL(5,4) NOT NULL,
    loss_given_default DECIMAL(5,4) NOT NULL,
    exposure_at_default DECIMAL(15,2) NOT NULL,
    expected_loss DECIMAL(15,2) GENERATED ALWAYS AS (exposure_at_default * probability_of_default * loss_given_default) STORED,
    risk_factors JSONB NOT NULL,
    protective_factors JSONB,
    mitigation_measures JSONB,
    model_version VARCHAR(20) NOT NULL,
    confidence_score DECIMAL(5,2),
    stress_test_results JSONB,
    regulatory_capital_required DECIMAL(15,2),
    economic_scenario VARCHAR(50) DEFAULT 'BASE_CASE',
    review_date DATE,
    next_assessment_due DATE,
    assessed_by VARCHAR(100) NOT NULL,
    approved_by VARCHAR(100),
    approval_date DATE,
    status VARCHAR(20) DEFAULT 'DRAFT',
    override_reason TEXT,
    override_approved_by VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    version INTEGER DEFAULT 0
);

INSERT INTO risk_assessments (assessment_id, customer_id, loan_id, application_id, assessment_date, assessment_type,
    risk_score, risk_category, probability_of_default, loss_given_default, exposure_at_default, risk_factors,
    model_version, assessed_by, approved_by, approval_date, status) VALUES
  ('RA-001', 1, '11111111-1111-1111-1111-111111111111', NULL, '2024-02-01', 'APPLICATION', 35.50, 'MEDIUM',
   0.0450, 0.4500, 50000.00, '{"dti": 0.32}', 'PD-2024.1', 'underwriter-1', 'credit-head', '2024-02-02', 'APPROVED'),
  ('RA-002', 2, NULL, 'APP-0002', '2024-03-05', 'APPLICATION', 72.00, 'HIGH',
   0.1800, 0.6000, 12000.00, '{"dti": 0.55, "delinquencies": 2}', 'PD-2024.1', 'underwriter-2', NULL, NULL, 'PENDING_APPROVAL'),
  ('RA-003', 1, '11111111-1111-1111-1111-111111111111', NULL, '2025-02-01', 'ANNUAL_REVIEW', 28.25, 'LOW',
   0.0200, 0.4000, 41000.00, '{"dti": 0.28}', 'PD-2025.1', 'risk-analyst-1', 'credit-head', '2025-02-03', 'APPROVED');
