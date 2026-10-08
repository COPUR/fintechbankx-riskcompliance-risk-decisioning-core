package com.bank.risk.infrastructure.outbox;

import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.infrastructure.web.CorrelationIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OutboxRiskEventPublisherTest {

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final OutboxRiskEventPublisher publisher =
        new OutboxRiskEventPublisher(outbox, new RiskEventEnvelopeFactory(new ObjectMapper()));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static RiskAssessment assessment(String transactionId) {
        return RiskAssessment.create(new RiskEvaluationCommand(transactionId, new BigDecimal("12000.00"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 60, RiskDecision.REVIEW,
            List.of("HIGH_AMOUNT", "MEDIUM_VELOCITY"), "rsk-policy-v2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesOneRowPerEventWithTheRequestCorrelationId() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-req");
        RiskAssessment assessment = assessment("PAY-PUB-1");

        publisher.publish(assessment.getDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).extracting(OutboxEventJpaEntity::getTopic).containsExactly("evt.rsk.risk.assessed.v1");
        assertThat(rows.getValue()).extracting(OutboxEventJpaEntity::getAggregateId)
            .containsExactly(assessment.getId().getValue());
        assertThat(rows.getValue()).extracting(OutboxEventJpaEntity::getCorrelationId).containsOnly("corr-req");
    }

    @Test
    @SuppressWarnings("unchecked")
    void eventsRaisedOutsideARequestGetAFreshCorrelationId() {
        publisher.publish(assessment("PAY-PUB-2").getDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue().getFirst().getCorrelationId()).matches("[0-9a-f-]{36}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theActiveTraceIsStoredForTheRelay() {
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");

        publisher.publish(assessment("PAY-PUB-3").getDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue().getFirst().getTraceparent())
            .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutATraceNoTraceparentIsStored() {
        publisher.publish(assessment("PAY-PUB-4").getDomainEvents());

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue().getFirst().getTraceparent()).isNull();
    }

    @Test
    void noEventsMeansNoWrite() {
        publisher.publish(List.of());

        verify(outbox, never()).saveAll(any());
    }

    @Test
    void publishingRequiresTheCallersTransaction() throws Exception {
        Transactional tx = OutboxRiskEventPublisher.class.getMethod("publish", List.class)
            .getAnnotation(Transactional.class);

        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
