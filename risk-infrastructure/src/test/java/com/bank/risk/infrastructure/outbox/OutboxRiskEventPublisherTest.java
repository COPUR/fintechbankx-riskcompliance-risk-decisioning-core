package com.bank.risk.infrastructure.outbox;

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
        return RiskAssessment.create(transactionId, new BigDecimal("12000.00"), "AED", 60, RiskDecision.REVIEW,
            List.of("HIGH_AMOUNT", "MEDIUM_VELOCITY"));
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
