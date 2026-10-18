package com.bank.risk.domain.port.in;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/** The loan service's question on {@code POST /api/v1/risk/loan-eligibility}. */
public record LoanEligibilityCommand(String customerId, BigDecimal requestedAmount, String currency) {

    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public LoanEligibilityCommand {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (customerId.length() > TransactionAssessmentCommand.MAX_CUSTOMER_ID_LENGTH) {
            throw new IllegalArgumentException("customerId must be at most " + TransactionAssessmentCommand.MAX_CUSTOMER_ID_LENGTH + " characters");
        }
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new IllegalArgumentException("requestedAmount must be positive");
        }
        if (requestedAmount.stripTrailingZeros().scale() > RiskEvaluationCommand.MAX_DECIMALS) {
            throw new IllegalArgumentException("requestedAmount must have at most " + RiskEvaluationCommand.MAX_DECIMALS + " decimals");
        }
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches() || !isIsoCurrency(currency)) {
            throw new IllegalArgumentException("currency must be an upper-case ISO 4217 code");
        }
    }

    private static boolean isIsoCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }
}
