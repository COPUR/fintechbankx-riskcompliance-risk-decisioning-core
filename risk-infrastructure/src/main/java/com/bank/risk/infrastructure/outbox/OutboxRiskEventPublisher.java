package com.bank.risk.infrastructure.outbox;

import com.bank.risk.domain.RiskDomainEvent;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.infrastructure.web.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox: writes each event's envelope in the caller's
 * transaction (MANDATORY), so the assessment row and its event commit or roll
 * back together. {@link OutboxRelay} ships them to Kafka afterwards.
 */
@Component
public class OutboxRiskEventPublisher implements RiskEventPublisher {

    private final SpringDataOutboxRepository outbox;
    private final RiskEventEnvelopeFactory envelopes;

    public OutboxRiskEventPublisher(SpringDataOutboxRepository outbox, RiskEventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(List<RiskDomainEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        String correlationId = currentCorrelationId();
        String traceparent = TraceContext.currentTraceparent();
        outbox.saveAll(events.stream()
            .map(event -> envelopes.toOutboxRow(event, correlationId, traceparent))
            .toList());
    }

    private static String currentCorrelationId() {
        String fromRequest = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }
}
