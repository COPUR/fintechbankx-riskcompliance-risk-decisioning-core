package com.bank.risk.infrastructure.outbox;

import com.bank.risk.domain.PaymentType;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.RiskAssessedEvent;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskEventEnvelopeFactoryTest {

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    private final ObjectMapper json = new ObjectMapper();
    private final RiskEventEnvelopeFactory factory = new RiskEventEnvelopeFactory(json);

    private static RiskAssessment blocked() {
        return RiskAssessment.create(new RiskEvaluationCommand("PAY-ENV-1", new BigDecimal("60000.5"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 100, RiskDecision.BLOCK,
            List.of("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "HIGH_VELOCITY"), "rsk-policy-v2");
    }

    @Test
    void assessedEventIsAnEnvelopeOnTheContractTopicKeyedByTheAssessment() throws Exception {
        RiskAssessment assessment = blocked();
        RiskAssessedEvent event = (RiskAssessedEvent) assessment.getDomainEvents().getFirst();

        OutboxEventJpaEntity row = factory.toOutboxRow(event, "corr-env-1", TRACEPARENT);
        JsonNode envelope = json.readTree(row.getPayload());

        assertThat(row.getEventId()).isEqualTo(event.eventId());
        assertThat(row.getTopic()).isEqualTo("evt.rsk.risk.v1");
        assertThat(row.getEventType()).isEqualTo("Risk.RiskAssessment.Assessed.v1");
        assertThat(row.getAggregateType()).isEqualTo("RiskAssessment");
        assertThat(row.getAggregateId()).isEqualTo(assessment.getId().getValue());
        assertThat(row.getAggregateVersion()).isZero();
        assertThat(row.getCorrelationId()).isEqualTo("corr-env-1");
        assertThat(row.getOccurredAt()).isEqualTo(assessment.getAssessedAt());
        assertThat(row.getTraceparent()).isEqualTo(TRACEPARENT);

        assertThat(envelope.fieldNames()).toIterable().containsExactly("eventId", "eventType", "occurredAt",
            "aggregateId", "aggregateVersion", "correlationId", "causationId", "producer", "data");
        assertThat(envelope.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-rsk-decisioning");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(assessment.getId().getValue());
        assertThat(envelope.get("aggregateVersion").asLong()).isZero();
        assertThat(envelope.get("causationId").isNull()).isTrue();
        assertThat(envelope.get("occurredAt").asText()).isEqualTo(assessment.getAssessedAt().toString());
    }

    @Test
    void dataCarriesTheDecisionFactsWithMoneyAsADecimalString() throws Exception {
        RiskAssessment assessment = blocked();

        JsonNode data = json.readTree(factory.toOutboxRow(assessment.getDomainEvents().getFirst(), "c", null).getPayload())
            .get("data");

        assertThat(data.fieldNames()).toIterable().containsExactly("assessmentId", "transactionId", "decision",
            "score", "amount", "reasons", "assessedAt", "attestationSource");
        assertThat(data.get("attestationSource").asText()).isEqualTo("CALLER_ATTESTED");
        assertThat(data.get("assessmentId").asText()).isEqualTo(assessment.getId().getValue());
        assertThat(data.get("transactionId").asText()).isEqualTo("PAY-ENV-1");
        assertThat(data.get("decision").asText()).isEqualTo("BLOCK");
        assertThat(data.get("score").asInt()).isEqualTo(100);
        assertThat(data.at("/amount/amount").isTextual()).isTrue();
        assertThat(data.at("/amount/amount").asText()).isEqualTo("60000.5");
        assertThat(data.at("/amount/currency").asText()).isEqualTo("AED");
        assertThat(data.get("reasons")).extracting(JsonNode::asText)
            .containsExactly("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "HIGH_VELOCITY");
        assertThat(data.get("assessedAt").asText()).isEqualTo(assessment.getAssessedAt().toString());
    }

    @Test
    void largeAmountsAreNeverWrittenInScientificNotation() throws Exception {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-ENV-2", new BigDecimal("1E+6"), "AED", false, 0, "svc-pay-initiation-settlement", PaymentType.TRANSFER), 50,
            RiskDecision.REVIEW, List.of("HIGH_AMOUNT", "VERY_HIGH_AMOUNT"), "rsk-policy-v2");

        JsonNode data = json.readTree(factory.toOutboxRow(assessment.getDomainEvents().getFirst(), "c", null).getPayload())
            .get("data");

        assertThat(data.at("/amount/amount").asText()).isEqualTo("1000000");
    }

    @Test
    void aSerialisationFailureIsReportedNotSwallowed() {
        ObjectMapper broken = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("boom") {
                };
            }
        };
        RiskEventEnvelopeFactory failing = new RiskEventEnvelopeFactory(broken);

        assertThatThrownBy(() -> failing.toOutboxRow(blocked().getDomainEvents().getFirst(), "c", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Cannot serialise");
    }
}
