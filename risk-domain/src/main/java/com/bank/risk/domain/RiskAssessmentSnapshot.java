package com.bank.risk.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Persisted state of a {@link RiskAssessment}, used by persistence adapters to
 * rebuild the assessment exactly as it was decided. customerId and
 * counterpartyCountry (V13) are null on caller-attested (v1) decisions;
 * counterpartyCountry is also null when risk could not resolve the country.
 */
public record RiskAssessmentSnapshot(
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
        String attestedBy,
        String ruleSetVersion,
        PaymentType paymentType,
        String customerId,
        String counterpartyCountry
) {
    /** A caller-attested decision: no customer, no resolved country. */
    public RiskAssessmentSnapshot(
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
            String attestedBy,
            String ruleSetVersion,
            PaymentType paymentType
    ) {
        this(id, transactionId, amount, currency, highRiskCountry, velocityScore, score, decision, reasons, assessedAt,
                attestationSource, attestedBy, ruleSetVersion, paymentType, null, null);
    }
}
