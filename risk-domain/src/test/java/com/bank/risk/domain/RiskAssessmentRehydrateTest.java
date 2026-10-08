package com.bank.risk.domain;

import com.bank.risk.domain.command.RiskEvaluationCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskAssessmentRehydrateTest {

    private static final Instant DECIDED = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void rehydrateKeepsTheStoredDecision() {
        RiskAssessment stored = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-1"), "TX-1", new BigDecimal("12000.00"), "AED", false, 0, 85,
                RiskDecision.BLOCK, List.of("HIGH_AMOUNT", "HIGH_RISK_COUNTRY"), DECIDED));

        assertThat(stored.getId()).isEqualTo(RiskAssessmentId.of("RISK-1"));
        assertThat(stored.getScore()).isEqualTo(85);
        assertThat(stored.isBlocked()).isTrue();
        assertThat(stored.getReasons()).containsExactly("HIGH_AMOUNT", "HIGH_RISK_COUNTRY");
        assertThat(stored.getAssessedAt()).isEqualTo(DECIDED);
    }

    @Test
    void rehydrateStillValidatesTheRow() {
        assertThatThrownBy(() -> RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-2"), "TX-2", new BigDecimal("1.00"), "AED", false, 0, 101,
                RiskDecision.ALLOW, List.of(), DECIDED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetryMatchesOnlyWhenItRepeatsEveryDecisionInput() {
        RiskAssessment assessment = RiskAssessment.create(
                new RiskEvaluationCommand("TX-3", new BigDecimal("250.0"), "AED", false, 45), 15,
                RiskDecision.ALLOW, List.of("MEDIUM_VELOCITY"));

        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "AED", false, 45)))
                .as("amount compared by value, not scale").isTrue();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "USD", false, 45))).isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.01"), "AED", false, 45))).isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "AED", true, 45)))
                .as("high-risk country flag flipped").isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "AED", false, 46)))
                .as("velocity score changed").isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-4", new BigDecimal("250.00"), "AED", false, 45)))
                .as("another transaction").isFalse();
    }

    @Test
    void theDecisionInputsAreKeptWithTheDecision() {
        RiskAssessment stored = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-5"), "TX-5", new BigDecimal("80.00"), "AED", true, 72, 70,
                RiskDecision.REVIEW, List.of("HIGH_RISK_COUNTRY", "HIGH_VELOCITY"), DECIDED));

        assertThat(stored.isHighRiskCountry()).isTrue();
        assertThat(stored.getVelocityScore()).isEqualTo(72);
        assertThatThrownBy(() -> RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-6"), "TX-6", new BigDecimal("80.00"), "AED", false, 101, 0,
                RiskDecision.ALLOW, List.of(), DECIDED)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("velocityScore");
    }
}
