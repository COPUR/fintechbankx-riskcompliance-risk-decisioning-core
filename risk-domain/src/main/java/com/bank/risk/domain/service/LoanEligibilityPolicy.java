package com.bank.risk.domain.service;

import com.bank.risk.domain.CustomerCreditProfile;
import com.bank.risk.domain.LoanEligibility;
import com.bank.risk.domain.LoanEligibilityDecision;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Loan eligibility, owned by risk ("Risk decides", screening-facts plan) and
 * ported from the customer context's archive rule so the two agree until
 * customer deletes its copy:
 * <ul>
 * <li>fintechbankx-customer-profile-kyc-core {@code customer-domain/.../Customer.java:22}
 * {@code MIN_CREDIT_SCORE_FOR_LOAN = 600} and {@code :410-413}
 * {@code isEligibleForLoan}: {@code creditScore != null && creditScore >= 600 && canBorrowAmount(loanAmount)};</li>
 * <li>{@code CreditProfile.java:56-61} {@code canBorrow}: the amount is
 * positive and no larger than the available credit (limit minus used);</li>
 * <li>{@code Customer.java:155-172} {@code calculateCreditLimit}: monthly
 * income times 3, times 4 from score 650, times 5 from score 750
 * ({@link #scoreDerivedLimit}). The credit limit the customer service
 * publishes was derived with that table; the function is kept here so risk
 * can recompute it once the credit contract carries the income.</li>
 * </ul>
 * The reasons are stable codes for the loan service and the audit trail.
 */
public final class LoanEligibilityPolicy {

    public static final int MIN_CREDIT_SCORE_FOR_LOAN = 600;
    public static final int MULTIPLIER_FOUR_FROM_SCORE = 650;
    public static final int MULTIPLIER_FIVE_FROM_SCORE = 750;

    public static final String WITHIN_AVAILABLE_CREDIT = "WITHIN_AVAILABLE_CREDIT";
    public static final String NO_CREDIT_SCORE = "NO_CREDIT_SCORE";
    public static final String CREDIT_SCORE_BELOW_MINIMUM = "CREDIT_SCORE_BELOW_MINIMUM";
    public static final String CURRENCY_MISMATCH = "CURRENCY_MISMATCH";
    public static final String INSUFFICIENT_AVAILABLE_CREDIT = "INSUFFICIENT_AVAILABLE_CREDIT";

    public LoanEligibility decide(CustomerCreditProfile profile, BigDecimal requestedAmount, String currency) {
        Objects.requireNonNull(profile, "profile is required");
        Objects.requireNonNull(currency, "currency is required");
        if (requestedAmount == null || requestedAmount.signum() <= 0) {
            throw new IllegalArgumentException("requestedAmount must be positive");
        }
        // Customer.java:411, in the aggregate's order: the score first, then the credit profile.
        if (profile.creditScore() == null) {
            return new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, NO_CREDIT_SCORE);
        }
        if (profile.creditScore() < MIN_CREDIT_SCORE_FOR_LOAN) {
            return new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, CREDIT_SCORE_BELOW_MINIMUM);
        }
        if (!profile.currency().equals(currency)) {
            return new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, CURRENCY_MISMATCH);
        }
        // CreditProfile.java:60: available credit must cover the amount.
        if (profile.availableCredit().compareTo(requestedAmount) < 0) {
            return new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, INSUFFICIENT_AVAILABLE_CREDIT);
        }
        return new LoanEligibility(LoanEligibilityDecision.ELIGIBLE, WITHIN_AVAILABLE_CREDIT);
    }

    /** Customer.java:164-171: the base multiplier is 3, 4 from score 650, 5 from score 750; same scale as the income. */
    public static BigDecimal scoreDerivedLimit(BigDecimal monthlyIncome, int creditScore) {
        Objects.requireNonNull(monthlyIncome, "monthlyIncome is required");
        BigDecimal multiplier = new BigDecimal("3");
        if (creditScore >= MULTIPLIER_FIVE_FROM_SCORE) {
            multiplier = new BigDecimal("5");
        } else if (creditScore >= MULTIPLIER_FOUR_FROM_SCORE) {
            multiplier = new BigDecimal("4");
        }
        return monthlyIncome.multiply(multiplier);
    }
}
