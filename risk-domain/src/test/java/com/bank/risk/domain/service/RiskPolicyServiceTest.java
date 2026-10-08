package com.bank.risk.domain.service;

import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskPolicyServiceTest {

    private final RiskPolicyService service = new RiskPolicyService();

    @Test
    void shouldAllowLowRiskTransaction() {
        var command = new RiskEvaluationCommand("TX-LOW", new BigDecimal("100"), "USD", false, 5, "svc-pay-initiation-settlement", PaymentType.TRANSFER);

        var assessment = service.evaluate(command);

        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(assessment.getScore()).isLessThan(50);
    }

    @Test
    void shouldRequireReviewForMediumRiskTransaction() {
        var command = new RiskEvaluationCommand("TX-REV", new BigDecimal("12000.50"), "USD", false, 70, "svc-pay-initiation-settlement", PaymentType.TRANSFER);

        var assessment = service.evaluate(command);

        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(assessment.getReasons()).contains("HIGH_AMOUNT", "HIGH_VELOCITY");
    }

    @Test
    void shouldBlockForHighRiskSignals() {
        var command = new RiskEvaluationCommand("TX-BLOCK", new BigDecimal("60000"), "USD", true, 80, "svc-pay-initiation-settlement", PaymentType.TRANSFER);

        var assessment = service.evaluate(command);

        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(assessment.getScore()).isEqualTo(100);
        assertThat(assessment.isBlocked()).isTrue();
    }

    private static RiskEvaluationCommand command(String amount, String currency, boolean highRiskCountry, int velocity) {
        return new RiskEvaluationCommand("TX-CCY", new BigDecimal(amount), currency, highRiskCountry, velocity,
                "svc-pay-initiation-settlement", PaymentType.TRANSFER);
    }

    @Test
    void everyDecisionRecordsTheRuleSetThatMadeIt() {
        assertThat(RiskPolicyService.RULE_SET_VERSION).isEqualTo("rsk-policy-v3");
        assertThat(service.evaluate(command("100", "USD", false, 0)).getRuleSetVersion()).isEqualTo("rsk-policy-v3");
        assertThat(service.evaluate(command("100", "JPY", true, 80)).getRuleSetVersion()).isEqualTo("rsk-policy-v3");
    }

    @Test
    void usdThresholdsAreTheCurrentValuesAndExclusive() {
        assertThat(service.evaluate(command("10000.00", "USD", false, 0)).getReasons()).isEmpty();
        assertThat(service.evaluate(command("10000.01", "USD", false, 0)).getReasons()).containsExactly("HIGH_AMOUNT");
        assertThat(service.evaluate(command("49999.99", "USD", false, 0)).getReasons()).containsExactly("HIGH_AMOUNT");
        assertThat(service.evaluate(command("50000.00", "USD", false, 0)).getReasons())
                .as("50000.00 is not above the very-high threshold (it is a round amount, see v3)")
                .containsExactly("HIGH_AMOUNT", "SUSPICIOUS_ROUND_AMOUNT");
        assertThat(service.evaluate(command("50000.01", "USD", false, 0)).getReasons())
                .containsExactly("HIGH_AMOUNT", "VERY_HIGH_AMOUNT");
    }

    /** Review finding: JPY 60,000 (about USD 400) scored HIGH_AMOUNT + VERY_HIGH_AMOUNT and went to REVIEW. */
    @Test
    void aSmallAmountInACurrencyWithoutThresholdsIsNotScoredAgainstUsdFigures() {
        var assessment = service.evaluate(command("60000", "JPY", false, 0));

        assertThat(assessment.getReasons()).containsExactly("UNSUPPORTED_CURRENCY");
        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    /** Review finding: KWD 9,000 (about USD 29,000) was ALLOWed. */
    @Test
    void anAmountInACurrencyWithoutThresholdsIsNeverAllowed() {
        var assessment = service.evaluate(command("9000", "KWD", false, 0));

        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(assessment.getReasons()).containsExactly("UNSUPPORTED_CURRENCY");
        assertThat(assessment.getScore()).as("only the rules that could be applied are scored").isZero();
    }

    @Test
    void theNonAmountRulesStillApplyToAnUnsupportedCurrency() {
        var assessment = service.evaluate(command("9000", "AED", true, 80));

        assertThat(assessment.getReasons()).containsExactly("UNSUPPORTED_CURRENCY", "HIGH_RISK_COUNTRY", "HIGH_VELOCITY");
        assertThat(assessment.getScore()).isEqualTo(70);
        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    void thresholdsAreConfiguredPerCurrency() {
        RiskPolicyService policy = new RiskPolicyService(Map.of(
                "USD", new AmountThresholds(new BigDecimal("10000"), new BigDecimal("50000")),
                "JPY", new AmountThresholds(new BigDecimal("1500000"), new BigDecimal("7500000"))));

        assertThat(policy.evaluate(command("60000", "JPY", false, 0)).getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(policy.evaluate(command("7500001", "JPY", false, 0)).getReasons())
                .containsExactly("HIGH_AMOUNT", "VERY_HIGH_AMOUNT");
        assertThat(policy.evaluate(command("9000", "KWD", false, 0)).getReasons()).containsExactly("UNSUPPORTED_CURRENCY");
    }

    /**
     * rsk-policy-v3, monolith parity (payment-context FraudDetectionServiceAdapter.isSuspiciousPattern):
     * a round amount, a multiple of 1000 above 10000, is refused as fraud. LP-05 found 12000.00 USD ALLOWed.
     */
    @Test
    void aRoundUsdAmountAbove10000IsBlockedAsSuspicious() {
        var twelveThousand = service.evaluate(command("12000.00", "USD", false, 0));

        assertThat(twelveThousand.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(twelveThousand.getReasons()).containsExactly("HIGH_AMOUNT", "SUSPICIOUS_ROUND_AMOUNT");
        assertThat(twelveThousand.getScore()).as("the score is the v2 score; the pattern decides").isEqualTo(30);
        assertThat(service.evaluate(command("11000", "USD", false, 0)).getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(service.evaluate(command("1000000.0000", "USD", false, 0)).getReasons())
                .contains("SUSPICIOUS_ROUND_AMOUNT");
    }

    @Test
    void roundAmountEdges() {
        var tenThousand = service.evaluate(command("10000.00", "USD", false, 0));
        assertThat(tenThousand.getReasons()).as("10000 is not above 10000").isEmpty();
        assertThat(tenThousand.getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(service.evaluate(command("12000.50", "USD", false, 0)).getReasons()).containsExactly("HIGH_AMOUNT");
        assertThat(service.evaluate(command("10500.00", "USD", false, 0)).getReasons()).containsExactly("HIGH_AMOUNT");
        assertThat(service.evaluate(command("9000.00", "USD", false, 0)).getDecision()).isEqualTo(RiskDecision.ALLOW);
    }

    @Test
    void theRoundAmountRuleIsUsdOnlyLikeTheThresholds() {
        var yen = service.evaluate(command("12000", "JPY", false, 0));

        assertThat(yen.getReasons()).containsExactly("UNSUPPORTED_CURRENCY");
        assertThat(yen.getDecision()).isEqualTo(RiskDecision.REVIEW);
    }

    @Test
    void thresholdsMustBePositiveAndOrdered() {
        assertThatThrownBy(() -> new AmountThresholds(BigDecimal.ZERO, new BigDecimal("50000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AmountThresholds(new BigDecimal("50000"), new BigDecimal("10000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RiskPolicyService(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RiskPolicyService.USD_THRESHOLDS.keySet()).isEqualTo(java.util.Set.of("USD"));
        assertThat(List.of(RiskPolicyService.UNSUPPORTED_CURRENCY)).containsExactly("UNSUPPORTED_CURRENCY");
    }
}
