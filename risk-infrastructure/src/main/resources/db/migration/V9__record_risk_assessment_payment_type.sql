-- rsk-policy-v3 restores the monolith's suspicious-pattern refusals, one of
-- which depends on the payment type (MOBILE_PAYMENT above 5000 USD). The type
-- is a fact of the decision of record: stored, and compared on retry.
-- NOT NULL without a default: no decision of record is stored anywhere
-- persistent yet (CI and ephemeral databases only), and a default would invent
-- a fact the caller never stated.

ALTER TABLE risk_assessment ADD COLUMN payment_type VARCHAR(32) NOT NULL;

ALTER TABLE risk_assessment ADD CONSTRAINT ck_risk_assessment_payment_type
    CHECK (payment_type IN ('TRANSFER', 'WIRE_TRANSFER', 'ACH', 'CHECK', 'CREDIT_CARD', 'DEBIT_CARD',
                            'MOBILE_PAYMENT', 'LOAN_PAYMENT', 'BILL_PAYMENT'));

COMMENT ON COLUMN risk_assessment.payment_type IS 'Payment type stated by the caller (monolith PaymentType values).';
