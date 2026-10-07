-- svc-rsk-decisioning owns these tables. Schema: sc_rsk_decisioning (Flyway
-- runs with that schema as default, so names are unqualified).
--
-- Transaction risk decisions of record: written once per transaction, never
-- updated. transaction_id is the caller's id (for example a payment id) and
-- is unique, so a retried assessment returns the first decision.

CREATE TABLE risk_assessment (
    assessment_id   VARCHAR(64)    PRIMARY KEY,
    transaction_id  VARCHAR(128)   NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL,
    currency        VARCHAR(3)     NOT NULL,
    score           INTEGER        NOT NULL,
    decision        VARCHAR(16)    NOT NULL,
    reasons         JSONB          NOT NULL DEFAULT '[]'::jsonb,
    assessed_at     TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uq_risk_assessment_transaction UNIQUE (transaction_id),
    CONSTRAINT ck_risk_assessment_amount CHECK (amount > 0),
    CONSTRAINT ck_risk_assessment_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_risk_assessment_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_risk_assessment_decision CHECK (decision IN ('ALLOW', 'REVIEW', 'BLOCK')),
    CONSTRAINT ck_risk_assessment_reasons CHECK (jsonb_typeof(reasons) = 'array')
);

CREATE INDEX ix_risk_assessment_review_queue ON risk_assessment (assessed_at) WHERE decision = 'REVIEW';

COMMENT ON TABLE risk_assessment IS 'Transaction risk decisions of record; insert-only.';
