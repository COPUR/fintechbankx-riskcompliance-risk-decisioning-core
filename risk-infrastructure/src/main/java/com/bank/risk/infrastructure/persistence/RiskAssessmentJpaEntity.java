package com.bank.risk.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Row of sc_rsk_decisioning.risk_assessment. Assessments are decisions of
 * record: written once, never updated.
 */
@Entity
@Table(name = "risk_assessment")
public class RiskAssessmentJpaEntity {

    @Id
    @Column(name = "assessment_id", length = 64)
    private String assessmentId;

    @Column(name = "transaction_id", nullable = false, length = 128, updatable = false)
    private String transactionId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "high_risk_country", nullable = false, updatable = false)
    private boolean highRiskCountry;

    @Column(name = "velocity_score", nullable = false, updatable = false)
    private int velocityScore;

    @Column(name = "score", nullable = false, updatable = false)
    private int score;

    @Column(name = "decision", nullable = false, length = 16, updatable = false)
    private String decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<String> reasons;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private Instant assessedAt;

    @Column(name = "attestation_source", nullable = false, length = 32, updatable = false)
    private String attestationSource;

    @Column(name = "attested_by", length = 255, updatable = false)
    private String attestedBy;

    protected RiskAssessmentJpaEntity() {
    }

    RiskAssessmentJpaEntity(String assessmentId, String transactionId, BigDecimal amount, String currency,
                            boolean highRiskCountry, int velocityScore, int score,
                            String decision, List<String> reasons, Instant assessedAt,
                            String attestationSource, String attestedBy) {
        this.assessmentId = assessmentId;
        this.transactionId = transactionId;
        this.amount = amount;
        this.currency = currency;
        this.highRiskCountry = highRiskCountry;
        this.velocityScore = velocityScore;
        this.score = score;
        this.decision = decision;
        this.reasons = reasons;
        this.assessedAt = assessedAt;
        this.attestationSource = attestationSource;
        this.attestedBy = attestedBy;
    }

    public String getAssessmentId() { return assessmentId; }
    public String getTransactionId() { return transactionId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public boolean isHighRiskCountry() { return highRiskCountry; }
    public int getVelocityScore() { return velocityScore; }
    public int getScore() { return score; }
    public String getDecision() { return decision; }
    public List<String> getReasons() { return reasons; }
    public Instant getAssessedAt() { return assessedAt; }
    public String getAttestationSource() { return attestationSource; }
    public String getAttestedBy() { return attestedBy; }
}
