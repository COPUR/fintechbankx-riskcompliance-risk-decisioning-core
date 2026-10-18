package com.bank.risk.domain.service;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Velocity is computed from risk's own decisions of record for the customer in
 * the trailing window (RISK_VELOCITY_WINDOW), then mapped onto the 0..100
 * velocityScore scale RiskPolicyService has always scored: 10 points per prior
 * assessment, capped at 100. So 4 prior assessments reach MEDIUM_VELOCITY
 * (40) and 7 reach HIGH_VELOCITY (70), the policy's existing thresholds.
 */
class VelocityPolicyTest {

    @ParameterizedTest(name = "{0} prior assessments -> velocityScore {1}")
    @CsvSource({
        "0, 0",
        "1, 10",
        "3, 30",
        "4, 40",
        "6, 60",
        "7, 70",
        "10, 100",
        "25, 100",
    })
    void priorAssessmentsMapOntoThePolicysVelocityScale(int prior, int score) {
        assertThat(VelocityPolicy.velocityScore(prior)).isEqualTo(score);
    }

    @org.junit.jupiter.api.Test
    void theMappingMeetsThePolicyThresholdsAtFourAndSeven() {
        assertThat(VelocityPolicy.velocityScore(3)).isLessThan(RiskPolicyService.MEDIUM_VELOCITY_FROM);
        assertThat(VelocityPolicy.velocityScore(4)).isEqualTo(RiskPolicyService.MEDIUM_VELOCITY_FROM);
        assertThat(VelocityPolicy.velocityScore(6)).isLessThan(RiskPolicyService.HIGH_VELOCITY_FROM);
        assertThat(VelocityPolicy.velocityScore(7)).isEqualTo(RiskPolicyService.HIGH_VELOCITY_FROM);
    }

    @org.junit.jupiter.api.Test
    void aNegativeCountIsAProgrammingError() {
        assertThatThrownBy(() -> VelocityPolicy.velocityScore(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
