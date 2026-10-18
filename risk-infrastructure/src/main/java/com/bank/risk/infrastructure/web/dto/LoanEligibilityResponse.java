package com.bank.risk.infrastructure.web.dto;

import com.bank.risk.domain.LoanEligibility;
import com.bank.risk.domain.port.in.LoanEligibilityCommand;

import java.math.BigDecimal;

/** Risk's answer: the question echoed, the decision and one reason code. */
public record LoanEligibilityResponse(
        String customerId,
        BigDecimal requestedAmount,
        String currency,
        String decision,
        String reason
) {
    public static LoanEligibilityResponse of(LoanEligibilityCommand question, LoanEligibility answer) {
        return new LoanEligibilityResponse(question.customerId(), question.requestedAmount(), question.currency(),
                answer.decision().name(), answer.reason());
    }
}
