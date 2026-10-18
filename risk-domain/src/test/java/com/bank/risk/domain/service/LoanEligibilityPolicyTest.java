package com.bank.risk.domain.service;

import com.bank.risk.domain.CustomerCreditProfile;
import com.bank.risk.domain.LoanEligibility;
import com.bank.risk.domain.LoanEligibilityDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loan eligibility moves from customer to risk ("Risk decides", screening-facts
 * plan). The rule is ported from fintechbankx-customer-profile-kyc-core:
 * <ul>
 * <li>{@code Customer.java:22} MIN_CREDIT_SCORE_FOR_LOAN = 600;</li>
 * <li>{@code Customer.java:410-413} isEligibleForLoan: creditScore != null
 * &amp;&amp; creditScore >= 600 &amp;&amp; canBorrowAmount(loanAmount);</li>
 * <li>{@code CreditProfile.java:56-61} canBorrow: a positive amount no larger
 * than the available credit (limit minus used);</li>
 * <li>{@code Customer.java:155-172} calculateCreditLimit: monthly income times
 * 3, or 4 from score 650, or 5 from score 750.</li>
 * </ul>
 * The parity figures are those of {@code CustomerTest.java:189-202}: income
 * 2500.00 AED at score 700 gives a 10000.00 limit; 5000.00 is eligible,
 * 12000.00 is not.
 */
class LoanEligibilityPolicyTest {

    private final LoanEligibilityPolicy policy = new LoanEligibilityPolicy();

    private static CustomerCreditProfile profile(Integer score, String limit, String used) {
        BigDecimal creditLimit = new BigDecimal(limit);
        BigDecimal usedCredit = new BigDecimal(used);
        return new CustomerCreditProfile("CUST-UNIT-006", "AED", creditLimit, usedCredit, creditLimit.subtract(usedCredit),
                score, null);
    }

    /** CustomerTest.java:189-202, with the limit customer derives from income 2500.00 at score 700 (4x). */
    @Test
    void parityWithCustomerTestIsEligibleForLoanShouldRequireScoreAndAvailability() {
        CustomerCreditProfile nora = profile(700, "10000.00", "0.00");

        assertThat(policy.decide(nora, new BigDecimal("5000.00"), "AED").decision()).isEqualTo(LoanEligibilityDecision.ELIGIBLE);
        assertThat(policy.decide(nora, new BigDecimal("12000.00"), "AED"))
            .isEqualTo(new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, LoanEligibilityPolicy.INSUFFICIENT_AVAILABLE_CREDIT));
    }

    /** Customer.java:22 and :411, the 600 minimum: 599 fails, 600 passes. */
    @ParameterizedTest(name = "score {0} -> {1}")
    @CsvSource({
        "599, INELIGIBLE",
        "600, ELIGIBLE",
        "850, ELIGIBLE",
    })
    void theSixHundredMinimumIsInclusive(int score, LoanEligibilityDecision expected) {
        LoanEligibility eligibility = policy.decide(profile(score, "10000.00", "0.00"), new BigDecimal("1000.00"), "AED");

        assertThat(eligibility.decision()).isEqualTo(expected);
        if (expected == LoanEligibilityDecision.INELIGIBLE) {
            assertThat(eligibility.reason()).isEqualTo(LoanEligibilityPolicy.CREDIT_SCORE_BELOW_MINIMUM);
        } else {
            assertThat(eligibility.reason()).isEqualTo(LoanEligibilityPolicy.WITHIN_AVAILABLE_CREDIT);
        }
    }

    /** Customer.java:411 creditScore != null: a profile without a score is never eligible (fail closed). */
    @Test
    void aMissingCreditScoreIsIneligible() {
        assertThat(policy.decide(profile(null, "10000.00", "0.00"), new BigDecimal("100.00"), "AED"))
            .isEqualTo(new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, LoanEligibilityPolicy.NO_CREDIT_SCORE));
    }

    /** CreditProfile.java:56-61: the amount must not exceed limit minus used; exactly the available credit passes. */
    @ParameterizedTest(name = "limit {0} used {1} requested {2} -> {3}")
    @CsvSource({
        "10000.00, 0.00,    10000.00, ELIGIBLE",
        "10000.00, 0.00,    10000.01, INELIGIBLE",
        "10000.00, 4000.00,  6000.00, ELIGIBLE",
        "10000.00, 4000.00,  6000.01, INELIGIBLE",
        "10000.00, 10000.00,    0.01, INELIGIBLE",
    })
    void theRequestedAmountMustFitTheAvailableCredit(String limit, String used, String requested, LoanEligibilityDecision expected) {
        assertThat(policy.decide(profile(700, limit, used), new BigDecimal(requested), "AED").decision()).isEqualTo(expected);
    }

    /** The score is checked first, as in Customer.isEligibleForLoan's short circuit: the reason names the score. */
    @Test
    void aLowScoreIsReportedBeforeTheAmount() {
        assertThat(policy.decide(profile(500, "100.00", "0.00"), new BigDecimal("1000.00"), "AED").reason())
            .isEqualTo(LoanEligibilityPolicy.CREDIT_SCORE_BELOW_MINIMUM);
    }

    /** Money.compareTo in customer refuses two currencies; here the mismatch is a reason, never a comparison of raw numbers. */
    @Test
    void aRequestInAnotherCurrencyThanTheCreditIsIneligible() {
        assertThat(policy.decide(profile(700, "10000.00", "0.00"), new BigDecimal("10.00"), "USD"))
            .isEqualTo(new LoanEligibility(LoanEligibilityDecision.INELIGIBLE, LoanEligibilityPolicy.CURRENCY_MISMATCH));
    }

    /** CreditProfile.canBorrow refuses zero and negative amounts; here they are not even a question. */
    @Test
    void aNonPositiveAmountIsRefused() {
        assertThatThrownBy(() -> policy.decide(profile(700, "10000.00", "0.00"), BigDecimal.ZERO, "AED"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
        assertThatThrownBy(() -> policy.decide(profile(700, "10000.00", "0.00"), new BigDecimal("-1"), "AED"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Customer.java:164-171: 3x income below 650, 4x from 650, 5x from 750; CustomerTest's 2500.00 at 700 is 10000.00. */
    @ParameterizedTest(name = "income {0} score {1} -> limit {2}")
    @CsvSource({
        "2500.00, 700, 10000.00",
        "2500.00, 649,  7500.00",
        "2500.00, 650, 10000.00",
        "2500.00, 749, 10000.00",
        "2500.00, 750, 12500.00",
        "1000.00, 300,  3000.00",
        "1234.56, 800,  6172.80",
    })
    void theScoreDerivedLimitMatchesCustomerCalculateCreditLimit(String income, int score, String limit) {
        assertThat(LoanEligibilityPolicy.scoreDerivedLimit(new BigDecimal(income), score)).isEqualByComparingTo(limit);
    }

    @Test
    void theProfileValueObjectKeepsAvailableCreditConsistent() {
        assertThatThrownBy(() -> new CustomerCreditProfile("C", "AED", new BigDecimal("100"), new BigDecimal("10"),
                new BigDecimal("80"), 700, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("availableCredit");
        assertThatThrownBy(() -> new CustomerCreditProfile("C", "aed", new BigDecimal("100"), new BigDecimal("10"),
                new BigDecimal("90"), 700, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
    }
}
