package com.bank.risk.domain;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskAssessedEventTest {

    private static final Instant DECIDED = Instant.parse("2026-10-08T09:15:30.123456Z");

    @Test
    void aNewAssessmentRaisesExactlyOneAssessedEventWithItsFacts() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-77", new BigDecimal("60000.00"), "AED", false, 0, "svc-pay-initiation-settlement"), 100,
                RiskDecision.BLOCK, List.of("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "HIGH_VELOCITY"), "rsk-policy-v2");

        assertThat(assessment.getDomainEvents()).hasSize(1);
        RiskAssessedEvent event = (RiskAssessedEvent) assessment.getDomainEvents().getFirst();
        assertThat(event.eventId()).isNotNull();
        assertThat(event.assessmentId()).isEqualTo(assessment.getId());
        assertThat(event.transactionId()).isEqualTo("PAY-77");
        assertThat(event.decision()).isEqualTo(RiskDecision.BLOCK);
        assertThat(event.score()).isEqualTo(100);
        assertThat(event.amount()).isEqualByComparingTo("60000.00");
        assertThat(event.currency()).isEqualTo("AED");
        assertThat(event.reasons()).containsExactly("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "HIGH_VELOCITY");
        assertThat(event.assessedAt()).isEqualTo(assessment.getAssessedAt());
        assertThat(event.occurredAt()).isEqualTo(assessment.getAssessedAt());
    }

    @Test
    void aRehydratedAssessmentRaisesNothingBecauseTheFactWasAlreadyPublished() {
        RiskAssessment stored = RiskAssessment.rehydrate(new RiskAssessmentSnapshot(
                RiskAssessmentId.of("RISK-STORED"), "PAY-78", new BigDecimal("100.00"), "AED", false, 0, 0,
                RiskDecision.ALLOW, List.of(), DECIDED, AttestationSource.CALLER_ATTESTED, "svc-pay-initiation-settlement", "rsk-policy-v2"));

        assertThat(stored.getDomainEvents()).isEmpty();
    }

    @Test
    void clearingTheEventsAfterPublishingEmptiesTheList() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-79", new BigDecimal("45.10"), "AED", false, 0, "svc-pay-initiation-settlement"), 15,
                RiskDecision.ALLOW, List.of("MEDIUM_VELOCITY"), "rsk-policy-v2");

        assessment.clearDomainEvents();

        assertThat(assessment.getDomainEvents()).isEmpty();
    }

    @Test
    void theEventListCannotBeChangedFromOutside() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-80", new BigDecimal("1.00"), "AED", false, 0, "svc-pay-initiation-settlement"), 0,
                RiskDecision.ALLOW, List.of(), "rsk-policy-v2");

        assertThatThrownBy(() -> assessment.getDomainEvents().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theEventKeepsACopyOfTheReasons() {
        List<String> reasons = new ArrayList<>(List.of("HIGH_AMOUNT"));
        RiskAssessedEvent event = new RiskAssessedEvent(UUID.randomUUID(), DECIDED, RiskAssessmentId.of("RISK-1"),
                "PAY-81", RiskDecision.REVIEW, 60, new BigDecimal("12000.00"), "AED", reasons, DECIDED);

        reasons.add("TAMPERED");

        assertThat(event.reasons()).containsExactly("HIGH_AMOUNT");
    }

    @Test
    void everyFactIsRequired() {
        UUID id = UUID.randomUUID();
        RiskAssessmentId assessmentId = RiskAssessmentId.of("RISK-1");
        BigDecimal amount = new BigDecimal("1.00");

        assertThatThrownBy(() -> new RiskAssessedEvent(null, DECIDED, assessmentId, "PAY", RiskDecision.ALLOW, 0,
                amount, "AED", List.of(), DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("eventId");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, null, assessmentId, "PAY", RiskDecision.ALLOW, 0,
                amount, "AED", List.of(), DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("occurredAt");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, DECIDED, null, "PAY", RiskDecision.ALLOW, 0,
                amount, "AED", List.of(), DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("assessmentId");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, DECIDED, assessmentId, null, RiskDecision.ALLOW, 0,
                amount, "AED", List.of(), DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("transactionId");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, DECIDED, assessmentId, "PAY", null, 0,
                amount, "AED", List.of(), DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("decision");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, DECIDED, assessmentId, "PAY", RiskDecision.ALLOW, 0,
                null, "AED", List.of(), DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("amount");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, DECIDED, assessmentId, "PAY", RiskDecision.ALLOW, 0,
                amount, null, List.of(), DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, DECIDED, assessmentId, "PAY", RiskDecision.ALLOW, 0,
                amount, "AED", null, DECIDED)).isInstanceOf(NullPointerException.class).hasMessageContaining("reasons");
        assertThatThrownBy(() -> new RiskAssessedEvent(id, DECIDED, assessmentId, "PAY", RiskDecision.ALLOW, 0,
                amount, "AED", List.of(), null)).isInstanceOf(NullPointerException.class).hasMessageContaining("assessedAt");
    }
}
