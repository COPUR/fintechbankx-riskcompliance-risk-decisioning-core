package com.bank.risk.domain.port.in;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * Inputs of one risk decision. Every field is part of the decision of record:
 * a retry must repeat all of them to get the stored decision back.
 *
 * Limits match what the decision of record stores, so nothing is rounded or
 * truncated on the way in and a faithful retry always matches exactly:
 * transactionId up to 128 characters (unique across all callers, who
 * namespace their ids, for example PAY-...), amount positive with at most 15
 * integer digits and 4 decimals, currency an upper-case ISO 4217 code,
 * velocityScore 0 to 100.
 */
public record RiskEvaluationCommand(
        String transactionId,
        BigDecimal amount,
        String currency,
        boolean highRiskCountry,
        int velocityScore
) {
    public static final int MAX_TRANSACTION_ID_LENGTH = 128;
    public static final int MAX_INTEGER_DIGITS = 15;
    public static final int MAX_DECIMALS = 4;
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public RiskEvaluationCommand {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required");
        }
        if (transactionId.length() > MAX_TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must be at most " + MAX_TRANSACTION_ID_LENGTH + " characters");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        // Trailing zeros carry no value, so 10.500000 is accepted as 10.5; 1.23456 is refused, not rounded.
        BigDecimal significant = amount.stripTrailingZeros();
        if (significant.scale() > MAX_DECIMALS) {
            throw new IllegalArgumentException("amount must have at most " + MAX_DECIMALS + " decimals");
        }
        if (significant.precision() - significant.scale() > MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("amount must have at most " + MAX_INTEGER_DIGITS + " integer digits");
        }
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches() || !isIsoCurrency(currency)) {
            throw new IllegalArgumentException("currency must be an upper-case ISO 4217 code");
        }
        if (velocityScore < 0 || velocityScore > 100) {
            throw new IllegalArgumentException("velocityScore must be between 0 and 100");
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
