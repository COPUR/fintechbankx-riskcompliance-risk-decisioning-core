package com.bank.risk.infrastructure.web.dto;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Body of POST /api/v1/risk/assess. highRiskCountry and velocityScore are
 * risk facts stated by the caller; they are boxed so an omitted or null value
 * is a 400 INVALID_REQUEST, never read as the low-risk false or 0.
 */
public record EvaluateRiskRequest(
        String transactionId,
        BigDecimal amount,
        String currency,
        @NotNull(message = "highRiskCountry is required") Boolean highRiskCountry,
        @NotNull(message = "velocityScore is required") Integer velocityScore
) {
    public RiskEvaluationCommand toCommand() {
        if (highRiskCountry == null) {
            throw new IllegalArgumentException("highRiskCountry is required");
        }
        if (velocityScore == null) {
            throw new IllegalArgumentException("velocityScore is required");
        }
        return new RiskEvaluationCommand(transactionId, amount, currency, highRiskCountry, velocityScore);
    }
}
