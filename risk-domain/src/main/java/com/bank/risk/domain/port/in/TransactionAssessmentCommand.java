package com.bank.risk.domain.port.in;

import com.bank.risk.domain.PaymentType;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * Inputs of one risk decision on {@code POST /api/v2/risk/assess}: the caller
 * sends ids and amounts only (transaction, customer, amount, currency, payment
 * type, counterparty account). Risk resolves highRiskCountry and velocityScore
 * itself. The caller's inputs are the decision of record's question: a retry
 * must repeat them (amount by value, the counterparty by the country it
 * resolves to) to get the stored decision; the resolved facts are recorded,
 * not compared, because velocity moves with time.
 *
 * Limits are those of {@link RiskEvaluationCommand}; customerId and
 * counterpartyAccount are at most 64 characters (an IBAN is at most 34).
 * requestedBy names the calling client (azp) or staff subject; it is recorded
 * as attested_by, not compared on retry.
 */
public record TransactionAssessmentCommand(
        String transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        PaymentType paymentType,
        String counterpartyAccount,
        String requestedBy
) {
    public static final int MAX_CUSTOMER_ID_LENGTH = 64;
    public static final int MAX_COUNTERPARTY_ACCOUNT_LENGTH = 64;
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public TransactionAssessmentCommand {
        if (transactionId == null || transactionId.isBlank()) {
            throw new IllegalArgumentException("transactionId is required");
        }
        if (transactionId.length() > RiskEvaluationCommand.MAX_TRANSACTION_ID_LENGTH) {
            throw new IllegalArgumentException("transactionId must be at most " + RiskEvaluationCommand.MAX_TRANSACTION_ID_LENGTH + " characters");
        }
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        // Canonical as the customer context keeps it (shared-kernel CustomerId.of trims): one customer, one velocity.
        customerId = customerId.trim();
        if (customerId.length() > MAX_CUSTOMER_ID_LENGTH) {
            throw new IllegalArgumentException("customerId must be at most " + MAX_CUSTOMER_ID_LENGTH + " characters");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        BigDecimal significant = amount.stripTrailingZeros();
        if (significant.scale() > RiskEvaluationCommand.MAX_DECIMALS) {
            throw new IllegalArgumentException("amount must have at most " + RiskEvaluationCommand.MAX_DECIMALS + " decimals");
        }
        if (significant.precision() - significant.scale() > RiskEvaluationCommand.MAX_INTEGER_DIGITS) {
            throw new IllegalArgumentException("amount must have at most " + RiskEvaluationCommand.MAX_INTEGER_DIGITS + " integer digits");
        }
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches() || !isIsoCurrency(currency)) {
            throw new IllegalArgumentException("currency must be an upper-case ISO 4217 code");
        }
        if (paymentType == null) {
            throw new IllegalArgumentException("paymentType is required");
        }
        if (counterpartyAccount == null || counterpartyAccount.isBlank()) {
            throw new IllegalArgumentException("counterpartyAccount is required");
        }
        if (counterpartyAccount.length() > MAX_COUNTERPARTY_ACCOUNT_LENGTH) {
            throw new IllegalArgumentException("counterpartyAccount must be at most " + MAX_COUNTERPARTY_ACCOUNT_LENGTH + " characters");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("requestedBy (the authenticated caller) is required");
        }
        if (requestedBy.length() > RiskEvaluationCommand.MAX_ATTESTED_BY_LENGTH) {
            throw new IllegalArgumentException("requestedBy must be at most " + RiskEvaluationCommand.MAX_ATTESTED_BY_LENGTH + " characters");
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
