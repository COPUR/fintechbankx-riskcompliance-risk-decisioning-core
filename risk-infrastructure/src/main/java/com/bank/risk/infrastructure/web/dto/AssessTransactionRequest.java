package com.bank.risk.infrastructure.web.dto;

import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.port.in.TransactionAssessmentCommand;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Body of POST /api/v2/risk/assess: ids and amounts only. highRiskCountry and
 * velocityScore are declared so that a caller still sending them (a v1 body)
 * gets a 400 that names the field, instead of having its statement silently
 * ignored: on v2 risk resolves both facts itself.
 */
public record AssessTransactionRequest(
        String transactionId,
        @NotNull(message = "customerId is required") String customerId,
        BigDecimal amount,
        String currency,
        @NotNull(message = "paymentType is required") String paymentType,
        @NotNull(message = "counterpartyAccount is required") String counterpartyAccount,
        Object highRiskCountry,
        Object velocityScore
) {
    /** @param requestedBy the authenticated caller (client id or staff subject; see CallerAttestation) */
    public TransactionAssessmentCommand toCommand(String requestedBy) {
        if (highRiskCountry != null) {
            throw new IllegalArgumentException("highRiskCountry is not accepted on /api/v2/risk/assess: risk resolves it from counterpartyAccount");
        }
        if (velocityScore != null) {
            throw new IllegalArgumentException("velocityScore is not accepted on /api/v2/risk/assess: risk computes it from the customer's assessments");
        }
        return new TransactionAssessmentCommand(transactionId, customerId, amount, currency, paymentType(paymentType),
                counterpartyAccount, requestedBy);
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
