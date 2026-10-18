package com.bank.risk.infrastructure.web.dto;

import com.bank.risk.domain.port.in.LoanEligibilityCommand;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Body of POST /api/v1/risk/loan-eligibility. */
public record LoanEligibilityRequest(
        @NotNull(message = "customerId is required") String customerId,
        @NotNull(message = "requestedAmount is required") BigDecimal requestedAmount,
        @NotNull(message = "currency is required") String currency
) {
    public LoanEligibilityCommand toCommand() {
        return new LoanEligibilityCommand(customerId, requestedAmount, currency);
    }
}
