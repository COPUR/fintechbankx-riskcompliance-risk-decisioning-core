package com.bank.risk.domain;

import com.bank.risk.domain.command.RiskEvaluationCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskAssessmentTest {

    @Test
    void shouldCreateAssessmentAndExposeBehavior() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("TX-1", new BigDecimal("500"), "AED", false, 0),
                55,
                RiskDecision.REVIEW,
                List.of("MEDIUM_VELOCITY")
        );

        assertThat(assessment.getId().getValue()).startsWith("RISK-");
        assertThat(assessment.getTransactionId()).isEqualTo("TX-1");
        assertThat(assessment.getScore()).isEqualTo(55);
        assertThat(assessment.requiresManualReview()).isTrue();
        assertThat(assessment.isBlocked()).isFalse();
    }

    @Test
    void shouldRejectInvalidScoreAndIdentifiers() {
        assertThatThrownBy(() -> RiskAssessment.create(new RiskEvaluationCommand("", new BigDecimal("10"), "AED", false, 0), 10, RiskDecision.ALLOW, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");

        assertThatThrownBy(() -> RiskAssessment.create(new RiskEvaluationCommand("TX-1", new BigDecimal("10"), "AED", false, 0), 101, RiskDecision.BLOCK, List.of("x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
    }
}
