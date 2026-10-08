package com.bank.risk.domain.service;

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
        var command = new RiskEvaluationCommand("TX-LOW", new BigDecimal("100"), "USD", false, 5, "svc-pay-initiation-settlement");

        var assessment = service.evaluate(command);

        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.ALLOW);
        assertThat(assessment.getScore()).isLessThan(50);
    }

    @Test
    void shouldRequireReviewForMediumRiskTransaction() {
        var command = new RiskEvaluationCommand("TX-REV", new BigDecimal("12000"), "USD", false, 70, "svc-pay-initiation-settlement");

        var assessment = service.evaluate(command);

        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.REVIEW);
        assertThat(assessment.getReasons()).contains("HIGH_AMOUNT", "HIGH_VELOCITY");
    }

    @Test
    void shouldBlockForHighRiskSignals() {
        var command = new RiskEvaluationCommand("TX-BLOCK", new BigDecimal("60000"), "USD", true, 80, "svc-pay-initiation-settlement");

        var assessment = service.evaluate(command);

        assertThat(assessment.getDecision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(assessment.getScore()).isEqualTo(100);
        assertThat(assessment.isBlocked()).isTrue();
    }

    private static RiskEvaluationCommand command(String amount, String currency, boolean highRiskCountry, int velocity) {
        return new RiskEvaluationCommand("TX-CCY", new BigDecimal(amount), currency, highRiskCountry, velocity,
                "svc-pay-initiation-settlement");
    }

    @Test
    void everyDecisionRecordsTheRuleSetThatMadeIt() {
        assertThat(RiskPolicyService.RULE_SET_VERSION).isEqualTo("rsk-policy-v2");
        assertThat(service.evaluate(command("100", "USD", false, 0)).getRuleSetVersion()).isEqualTo("rsk-policy-v2");
        assertThat(service.evaluate(command("100", "JPY", true, 80)).getRuleSetVersion()).isEqualTo("rsk-policy-v2");
    }

    @Test
    void usdThresholdsAreTheCurrentValuesAndExclusive() {
        assertThat(service.evaluate(command("10000.00", "USD", false, 0)).getReasons()).isEmpty();
        assertThat(service.evaluate(command("10000.01", "USD", false, 0)).getReasons()).containsExactly("HIGH_AMOUNT");
        assertThat(service.evaluate(command("50000.00", "USD", false, 0)).getReasons()).containsExactly("HIGH_AMOUNT");
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
