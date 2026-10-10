package com.bank.risk.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A transaction received its risk decision of record. Raised once, when the
 * assessment is first made; a retry that returns the stored decision raises
 * nothing. Carries ids, the decision and reason codes only.
 */
public record RiskAssessedEvent(
        UUID eventId,
        Instant occurredAt,
        RiskAssessmentId assessmentId,
        String transactionId,
        RiskDecision decision,
        int score,
        BigDecimal amount,
        String currency,
        List<String> reasons,
        Instant assessedAt,
        AttestationSource attestationSource
) implements RiskDomainEvent {

    public RiskAssessedEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        Objects.requireNonNull(assessmentId, "assessmentId is required");
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(decision, "decision is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons are required"));
        Objects.requireNonNull(assessedAt, "assessedAt is required");
        Objects.requireNonNull(attestationSource, "attestationSource is required");
    }
}
