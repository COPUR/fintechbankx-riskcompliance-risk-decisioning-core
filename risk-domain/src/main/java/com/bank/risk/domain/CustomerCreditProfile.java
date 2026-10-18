package com.bank.risk.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A customer's credit standing as the customer context publishes it
 * ({@code GET /api/v1/customers/{id}/credit-standing}: customerId,
 * creditScore optional, creditLimit, usedCredit, availableCredit, currency),
 * read through an anti-corruption adapter. The credit score is nullable
 * (absent for a customer without one); without it the eligibility policy
 * answers INELIGIBLE, never ELIGIBLE by default. The monthly income is
 * nullable and not published today; it feeds the score-derived limit only.
 */
public record CustomerCreditProfile(
        String customerId,
        String currency,
        BigDecimal creditLimit,
        BigDecimal usedCredit,
        BigDecimal availableCredit,
        Integer creditScore,
        BigDecimal monthlyIncome
) {
    private static final Pattern CURRENCY_CODE = Pattern.compile("[A-Z]{3}");

    public CustomerCreditProfile {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (currency == null || !CURRENCY_CODE.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency must be an upper-case ISO 4217 code");
        }
        Objects.requireNonNull(creditLimit, "creditLimit is required");
        Objects.requireNonNull(usedCredit, "usedCredit is required");
        Objects.requireNonNull(availableCredit, "availableCredit is required");
        if (creditLimit.signum() < 0 || usedCredit.signum() < 0) {
            throw new IllegalArgumentException("creditLimit and usedCredit must not be negative");
        }
        if (creditLimit.subtract(usedCredit).compareTo(availableCredit) != 0) {
            throw new IllegalArgumentException("availableCredit must equal creditLimit minus usedCredit");
        }
    }
}
