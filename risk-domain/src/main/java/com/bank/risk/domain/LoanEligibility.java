package com.bank.risk.domain;

import java.util.Objects;

/**
 * Risk's answer to "may this customer borrow this amount": the decision and
 * one reason code ({@link com.bank.risk.domain.service.LoanEligibilityPolicy}).
 */
public record LoanEligibility(LoanEligibilityDecision decision, String reason) {

    public LoanEligibility {
        Objects.requireNonNull(decision, "decision is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required");
        }
    }

    public boolean isEligible() {
        return decision == LoanEligibilityDecision.ELIGIBLE;
    }
}
