package com.bank.risk.domain;

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
                RiskAssessmentId.of("RISK-1"), "TX-1", new BigDecimal("12000.00"), "AED", 85,
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
                RiskAssessmentId.of("RISK-2"), "TX-2", new BigDecimal("1.00"), "AED", 101,
                RiskDecision.ALLOW, List.of(), DECIDED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchesComparesCurrencyAndAmountIgnoringScale() {
        RiskAssessment assessment = RiskAssessment.create("TX-3", new BigDecimal("250.0"), "AED", 0,
                RiskDecision.ALLOW, List.of());

        assertThat(assessment.matches("AED", new BigDecimal("250.00"))).isTrue();
        assertThat(assessment.matches("USD", new BigDecimal("250.00"))).isFalse();
        assertThat(assessment.matches("AED", new BigDecimal("250.01"))).isFalse();
    }
}
