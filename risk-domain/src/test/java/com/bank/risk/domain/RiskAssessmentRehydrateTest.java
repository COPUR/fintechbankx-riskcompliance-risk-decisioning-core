package com.bank.risk.domain;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;
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
                RiskDecision.BLOCK, List.of("HIGH_AMOUNT", "HIGH_RISK_COUNTRY"), DECIDED, AttestationSource.CALLER_ATTESTED, "svc-pay-initiation-settlement"));

        assertThat(stored.getId()).isEqualTo(RiskAssessmentId.of("RISK-1"));
        assertThat(stored.getScore()).isEqualTo(85);
        assertThat(stored.isBlocked()).isTrue();
        assertThat(stored.getReasons()).containsExactly("HIGH_AMOUNT", "HIGH_RISK_COUNTRY");
        assertThat(stored.getAssessedAt()).isEqualTo(DECIDED);
    }

    @Test
    void rehydrateKeepsTheAttestationAndAcceptsAnUnknownAttesterForRowsDecidedBeforeItWasStored() {
        RiskAssessment attested = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-7"), "TX-7", new BigDecimal("1.00"), "USD", false, 0, 0,
                RiskDecision.ALLOW, List.of(), DECIDED, AttestationSource.CALLER_ATTESTED, "staff-7"));
        RiskAssessment legacy = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-8"), "TX-8", new BigDecimal("1.00"), "USD", false, 0, 0,
                RiskDecision.ALLOW, List.of(), DECIDED, AttestationSource.CALLER_ATTESTED, null));

        assertThat(attested.getAttestationSource()).isEqualTo(AttestationSource.CALLER_ATTESTED);
        assertThat(attested.getAttestedBy()).isEqualTo("staff-7");
        assertThat(legacy.getAttestedBy()).isNull();
        assertThatThrownBy(() -> RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-9"), "TX-9", new BigDecimal("1.00"), "USD", false, 0, 0,
                RiskDecision.ALLOW, List.of(), DECIDED, null, "staff-9")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("attestationSource");
    }

    @Test
    void rehydrateStillValidatesTheRow() {
        assertThatThrownBy(() -> RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-2"), "TX-2", new BigDecimal("1.00"), "AED", false, 0, 101,
                RiskDecision.ALLOW, List.of(), DECIDED, AttestationSource.CALLER_ATTESTED, "svc-pay-initiation-settlement")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRetryMatchesOnlyWhenItRepeatsEveryDecisionInput() {
        RiskAssessment assessment = RiskAssessment.create(
                new RiskEvaluationCommand("TX-3", new BigDecimal("250.0"), "AED", false, 45, "svc-pay-initiation-settlement"), 15,
                RiskDecision.ALLOW, List.of("MEDIUM_VELOCITY"));

        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "AED", false, 45, "svc-pay-initiation-settlement")))
                .as("amount compared by value, not scale").isTrue();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "USD", false, 45, "svc-pay-initiation-settlement"))).isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.01"), "AED", false, 45, "svc-pay-initiation-settlement"))).isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "AED", true, 45, "svc-pay-initiation-settlement")))
                .as("high-risk country flag flipped").isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-3", new BigDecimal("250.00"), "AED", false, 46, "svc-pay-initiation-settlement")))
                .as("velocity score changed").isFalse();
        assertThat(assessment.matches(new RiskEvaluationCommand("TX-4", new BigDecimal("250.00"), "AED", false, 45, "svc-pay-initiation-settlement")))
                .as("another transaction").isFalse();
    }

    @Test
    void theDecisionInputsAreKeptWithTheDecision() {
        RiskAssessment stored = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-5"), "TX-5", new BigDecimal("80.00"), "AED", true, 72, 70,
                RiskDecision.REVIEW, List.of("HIGH_RISK_COUNTRY", "HIGH_VELOCITY"), DECIDED, AttestationSource.CALLER_ATTESTED, "svc-pay-initiation-settlement"));

        assertThat(stored.isHighRiskCountry()).isTrue();
        assertThat(stored.getVelocityScore()).isEqualTo(72);
        assertThatThrownBy(() -> RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-6"), "TX-6", new BigDecimal("80.00"), "AED", false, 101, 0,
                RiskDecision.ALLOW, List.of(), DECIDED, AttestationSource.CALLER_ATTESTED, "svc-pay-initiation-settlement")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("velocityScore");
    }
    @Test
    void aRetryWithEveryInputRepeatedGetsTheStoredDecisionBack() {
        RiskAssessment stored = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-7"), "TX-7", new BigDecimal("12000.00"), "AED", false, 0, 85,
                RiskDecision.BLOCK, List.of("HIGH_AMOUNT"), DECIDED, AttestationSource.CALLER_ATTESTED, "svc-pay-initiation-settlement"));

        assertThat(stored.answerRetry(new RiskEvaluationCommand("TX-7", new BigDecimal("12000.0"), "AED", false, 0, "svc-pay-initiation-settlement")))
                .isSameAs(stored);
    }

    @Test
    void aRetryWithAnyOtherInputIsRefusedAndKeepsTheStoredDecision() {
        RiskAssessment stored = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-8"), "TX-8", new BigDecimal("12000.00"), "AED", false, 0, 85,
                RiskDecision.BLOCK, List.of("HIGH_AMOUNT"), DECIDED, AttestationSource.CALLER_ATTESTED, "svc-pay-initiation-settlement"));

        assertThatThrownBy(() -> stored.answerRetry(
                new RiskEvaluationCommand("TX-8", new BigDecimal("99.00"), "AED", false, 0, "svc-pay-initiation-settlement")))
                .isInstanceOf(TransactionAlreadyAssessedException.class)
                .hasMessage("Transaction TX-8 was already assessed with different inputs");
        assertThat(stored.isBlocked()).isTrue();
        assertThat(stored.getDomainEvents()).isEmpty();
    }
}
