package com.bank.risk.infrastructure.outbox;

import com.bank.risk.domain.RiskAssessedEvent;
import com.bank.risk.domain.RiskDomainEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns risk domain events into the public envelope of the AsyncAPI contract
 * api/asyncapi/svc-rsk-decisioning.yaml: topic evt.rsk.risk.assessed.v1,
 * eventType Risk.RiskAssessment.Assessed.v1, key and aggregateId the
 * assessment id, money as a decimal string. No customer or counterparty data.
 */
public class RiskEventEnvelopeFactory {

    public static final String PRODUCER = "svc-rsk-decisioning";
    public static final String AGGREGATE_TYPE = "RiskAssessment";
    public static final String ASSESSED_TOPIC = "evt.rsk.risk.assessed.v1";
    public static final String ASSESSED_EVENT_TYPE = "Risk.RiskAssessment.Assessed.v1";

    /** Assessments are insert-only, so every event is about version 0. */
    static final long AGGREGATE_VERSION = 0L;

    private final ObjectMapper objectMapper;

    public RiskEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param traceparent W3C trace context to forward as a record header, or null
     */
    public OutboxEventJpaEntity toOutboxRow(RiskDomainEvent event, String correlationId, String traceparent) {
        PublicEvent mapped = map(event);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("eventType", mapped.eventType());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("aggregateId", mapped.aggregateId());
        envelope.put("aggregateVersion", AGGREGATE_VERSION);
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", mapped.data());

        return new OutboxEventJpaEntity(event.eventId(), AGGREGATE_TYPE, mapped.aggregateId(), AGGREGATE_VERSION,
            mapped.eventType(), mapped.topic(), toJson(envelope), correlationId, event.occurredAt(), traceparent);
    }

    static PublicEvent map(RiskDomainEvent event) {
        return switch (event) {
            case RiskAssessedEvent e -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("assessmentId", e.assessmentId().getValue());
                data.put("transactionId", e.transactionId());
                data.put("decision", e.decision().name());
                data.put("score", e.score());
                Map<String, Object> amount = new LinkedHashMap<>();
                amount.put("amount", e.amount().toPlainString());
                amount.put("currency", e.currency());
                data.put("amount", amount);
                data.put("reasons", e.reasons());
                data.put("assessedAt", e.assessedAt().toString());
                // Optional in the contract; the attesting caller's id stays in the API.
                data.put("attestationSource", e.attestationSource().name());
                yield new PublicEvent(ASSESSED_TOPIC, ASSESSED_EVENT_TYPE, e.assessmentId().getValue(), data);
            }
        };
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise risk event envelope", e);
        }
    }

    record PublicEvent(String topic, String eventType, String aggregateId, Map<String, Object> data) {
    }
}
