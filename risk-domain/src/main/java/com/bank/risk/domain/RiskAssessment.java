package com.bank.risk.domain;

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
    private final int score;
    private final RiskDecision decision;
    private final List<String> reasons;
    private final Instant assessedAt;
    private final List<RiskDomainEvent> domainEvents = new ArrayList<>();

    private RiskAssessment(
            RiskAssessmentId id,
            String transactionId,
            BigDecimal amount,
            String currency,
            int score,
            RiskDecision decision,
            List<String> reasons,
            Instant assessedAt
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
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        this.transactionId = transactionId;
        this.amount = amount;
        this.currency = currency;
        this.score = score;
        this.decision = Objects.requireNonNull(decision, "decision is required");
        this.reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons are required"));
        this.assessedAt = Objects.requireNonNull(assessedAt, "assessedAt is required");
    }

    public static RiskAssessment create(
            String transactionId,
            BigDecimal amount,
            String currency,
            int score,
            RiskDecision decision,
            List<String> reasons
    ) {
        RiskAssessment assessment = new RiskAssessment(
                RiskAssessmentId.generate(),
                transactionId,
                amount,
                currency,
                score,
                decision,
                reasons,
                // Microseconds: the precision the decision of record is stored with, so a
                // retry returns exactly the timestamp the first response carried.
                Instant.now().truncatedTo(ChronoUnit.MICROS)
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
                snapshot.score(),
                snapshot.decision(),
                snapshot.reasons(),
                snapshot.assessedAt()
        );
    }

    /**
     * True when a repeated request for this transaction describes the same
     * transaction, so the stored decision can be returned for it.
     */
    public boolean matches(String otherCurrency, BigDecimal otherAmount) {
        return currency.equals(otherCurrency) && amount.compareTo(otherAmount) == 0;
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

    public boolean isBlocked() {
        return decision == RiskDecision.BLOCK;
    }

    public boolean requiresManualReview() {
        return decision == RiskDecision.REVIEW;
    }
}
