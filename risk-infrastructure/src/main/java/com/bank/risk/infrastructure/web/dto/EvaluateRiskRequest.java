package com.bank.risk.infrastructure.web.dto;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;

import java.math.BigDecimal;

public record EvaluateRiskRequest(
        String transactionId,
        BigDecimal amount,
        String currency,
        boolean highRiskCountry,
        int velocityScore
) {
    public RiskEvaluationCommand toCommand() {
        return new RiskEvaluationCommand(transactionId, amount, currency, highRiskCountry, velocityScore);
    }
}
