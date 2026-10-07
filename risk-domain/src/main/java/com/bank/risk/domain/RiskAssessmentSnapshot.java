package com.bank.risk.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Persisted state of a {@link RiskAssessment}, used by persistence adapters to
 * rebuild the assessment exactly as it was decided.
 */
public record RiskAssessmentSnapshot(
        RiskAssessmentId id,
        String transactionId,
        BigDecimal amount,
        String currency,
        int score,
        RiskDecision decision,
        List<String> reasons,
        Instant assessedAt
) {
}
