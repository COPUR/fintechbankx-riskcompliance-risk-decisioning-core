package com.bank.risk.domain;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RiskAssessment {
    private final RiskAssessmentId id;
    private final String transactionId;
    private final BigDecimal amount;
    private final String currency;
    private final boolean highRiskCountry;
    private final int velocityScore;
    private final int score;
    private final RiskDecision decision;
    private final List<String> reasons;
    private final Instant assessedAt;
    private final AttestationSource attestationSource;
    private final String attestedBy;
    private final List<RiskDomainEvent> domainEvents = new ArrayList<>();

    private RiskAssessment(
            RiskAssessmentId id,
            String transactionId,
            BigDecimal amount,
            String currency,
            boolean highRiskCountry,
            int velocityScore,
            int score,
            RiskDecision decision,
            List<String> reasons,
            Instant assessedAt,
            AttestationSource attestationSource,
            String attestedBy
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        if (velocityScore < 0 || velocityScore > 100) {
            throw new IllegalArgumentException("velocityScore must be between 0 and 100");
        }
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        this.transactionId = transactionId;
        this.amount = amount;
        this.currency = currency;
        this.highRiskCountry = highRiskCountry;
        this.velocityScore = velocityScore;
        this.score = score;
        this.decision = Objects.requireNonNull(decision, "decision is required");
        this.reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons are required"));
        this.assessedAt = Objects.requireNonNull(assessedAt, "assessedAt is required");
        this.attestationSource = Objects.requireNonNull(attestationSource, "attestationSource is required");
        this.attestedBy = attestedBy;
    }

    /**
     * Records the decision the policy made for these inputs. The inputs are
     * kept with the decision so a retry can be checked against all of them.
     */
    public static RiskAssessment create(
            RiskEvaluationCommand inputs,
            int score,
            RiskDecision decision,
            List<String> reasons
    ) {
        Objects.requireNonNull(inputs, "inputs are required");
        RiskAssessment assessment = new RiskAssessment(
                RiskAssessmentId.generate(),
                inputs.transactionId(),
                inputs.amount(),
                inputs.currency(),
                inputs.highRiskCountry(),
                inputs.velocityScore(),
                score,
                decision,
                reasons,
                // Microseconds: the precision the decision of record is stored with, so a
                // retry returns exactly the timestamp the first response carried.
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                // The policy only applies thresholds to the caller's statements.
                AttestationSource.CALLER_ATTESTED,
                inputs.attestedBy()
        );
        assessment.domainEvents.add(assessment.assessedEvent());
        return assessment;
    }

    /**
     * Rebuilds a stored assessment. The decision and score are kept as they
     * were made, even if the policy has changed since. Raises no event: the
     * assessment was announced when it was first made.
     */
    public static RiskAssessment rehydrate(RiskAssessmentSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot is required");
        return new RiskAssessment(
                snapshot.id(),
                snapshot.transactionId(),
                snapshot.amount(),
                snapshot.currency(),
                snapshot.highRiskCountry(),
                snapshot.velocityScore(),
                snapshot.score(),
                snapshot.decision(),
                snapshot.reasons(),
                snapshot.assessedAt(),
                snapshot.attestationSource(),
                snapshot.attestedBy()
        );
    }

    /**
     * True when a repeated request repeats every input of this decision
     * (amount compared by value, so 100.0 equals 100.00), so the stored
     * decision can be returned for it. Any other difference is a different
     * question under a reused transaction id.
     */
    public boolean matches(RiskEvaluationCommand request) {
        return transactionId.equals(request.transactionId())
                && currency.equals(request.currency())
                && amount.compareTo(request.amount()) == 0
                && highRiskCountry == request.highRiskCountry()
                && velocityScore == request.velocityScore();
    }

    /**
     * Answers a repeated request for this transaction: the stored decision when
     * every input is repeated (no new event is raised), otherwise
     * {@link TransactionAlreadyAssessedException}. A stored decision is never replaced.
     */
    public RiskAssessment answerRetry(RiskEvaluationCommand request) {
        if (!matches(request)) {
            throw new TransactionAlreadyAssessedException(request.transactionId());
        }
        return this;
    }

    private RiskAssessedEvent assessedEvent() {
        return new RiskAssessedEvent(UUID.randomUUID(), assessedAt, id, transactionId, decision, score,
                amount, currency, reasons, assessedAt);
    }

    /** Events raised since the assessment was created, oldest first; publish them after saving. */
    public List<RiskDomainEvent> getDomainEvents() {
        return List.copyOf(domainEvents);
    }

    public void clearDomainEvents() {
        domainEvents.clear();
    }

    public RiskAssessmentId getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isHighRiskCountry() {
        return highRiskCountry;
    }

    public int getVelocityScore() {
        return velocityScore;
    }

    public int getScore() {
        return score;
    }

    public RiskDecision getDecision() {
        return decision;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    public AttestationSource getAttestationSource() {
        return attestationSource;
    }

    /** The calling client (azp) or staff subject that stated the risk facts; null for decisions stored before V5. */
    public String getAttestedBy() {
        return attestedBy;
    }

    public boolean isBlocked() {
        return decision == RiskDecision.BLOCK;
    }

    public boolean requiresManualReview() {
        return decision == RiskDecision.REVIEW;
    }
}
