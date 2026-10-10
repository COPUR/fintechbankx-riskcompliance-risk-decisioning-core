package com.bank.risk.infrastructure.web.dto;

import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Body of POST /api/v1/risk/assess. highRiskCountry, velocityScore and
 * paymentType are risk facts stated by the caller; they are boxed or text so
 * an omitted, null or unknown value is a 400 INVALID_REQUEST, never read as a
 * low-risk default.
 */
public record EvaluateRiskRequest(
        String transactionId,
        BigDecimal amount,
        String currency,
        @NotNull(message = "highRiskCountry is required") Boolean highRiskCountry,
        @NotNull(message = "velocityScore is required") Integer velocityScore,
        @NotNull(message = "paymentType is required") String paymentType
) {
    /** @param attestedBy the authenticated caller stating the risk facts (see CallerAttestation) */
    public RiskEvaluationCommand toCommand(String attestedBy) {
        if (highRiskCountry == null) {
            throw new IllegalArgumentException("highRiskCountry is required");
        }
        if (velocityScore == null) {
            throw new IllegalArgumentException("velocityScore is required");
        }
        return new RiskEvaluationCommand(transactionId, amount, currency, highRiskCountry, velocityScore, attestedBy,
                paymentType(paymentType));
    }

    private static PaymentType paymentType(String value) {
        if (value == null) {
            throw new IllegalArgumentException("paymentType is required");
        }
        try {
            return PaymentType.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("paymentType must be one of " + Arrays.toString(PaymentType.values()));
        }
    }
}
