package com.bank.risk.domain;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskAssessmentTest {

    @Test
    void shouldCreateAssessmentAndExposeBehavior() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("TX-1", new BigDecimal("500"), "AED", false, 0, "svc-pay-initiation-settlement"),
                55,
                RiskDecision.REVIEW,
                List.of("MEDIUM_VELOCITY")
        , "rsk-policy-v2");

        assertThat(assessment.getId().getValue()).startsWith("RISK-");
        assertThat(assessment.getTransactionId()).isEqualTo("TX-1");
        assertThat(assessment.getScore()).isEqualTo(55);
        assertThat(assessment.requiresManualReview()).isTrue();
        assertThat(assessment.isBlocked()).isFalse();
    }

    @Test
    void aNewDecisionRecordsThatItRestsOnCallerAttestedFactsAndWhoAttestedThem() {
        RiskAssessment assessment = RiskAssessment.create(
                new RiskEvaluationCommand("PAY-9", new BigDecimal("9000.00"), "USD", false, 0, "svc-pay-initiation-settlement"),
                0, RiskDecision.ALLOW, List.of(), "rsk-policy-v2");

        assertThat(assessment.getAttestationSource()).isEqualTo(AttestationSource.CALLER_ATTESTED);
        assertThat(assessment.getAttestedBy()).isEqualTo("svc-pay-initiation-settlement");
    }

    @Test
    void theRuleSetVersionIsRequired() {
        for (String missing : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> RiskAssessment.create(
                    new RiskEvaluationCommand("TX-1", new BigDecimal("10"), "USD", false, 0, "svc-pay-initiation-settlement"),
                    0, RiskDecision.ALLOW, List.of(), missing))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ruleSetVersion");
        }
    }

    @Test
    void shouldRejectInvalidScoreAndIdentifiers() {
        assertThatThrownBy(() -> RiskAssessment.create(new RiskEvaluationCommand("", new BigDecimal("10"), "AED", false, 0, "svc-pay-initiation-settlement"), 10, RiskDecision.ALLOW, List.of(), "rsk-policy-v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transactionId");

        assertThatThrownBy(() -> RiskAssessment.create(new RiskEvaluationCommand("TX-1", new BigDecimal("10"), "AED", false, 0, "svc-pay-initiation-settlement"), 101, RiskDecision.BLOCK, List.of("x"), "rsk-policy-v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("score");
    }
}
