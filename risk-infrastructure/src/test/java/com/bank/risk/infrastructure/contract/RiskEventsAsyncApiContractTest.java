package com.bank.risk.infrastructure.contract;

import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.infrastructure.outbox.OutboxEventJpaEntity;
import com.bank.risk.infrastructure.outbox.RiskEventEnvelopeFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the envelope the outbox writes against the provider-owned AsyncAPI
 * contract api/asyncapi/svc-rsk-decisioning.yaml and the shared envelope
 * schema next to it: topic, eventType, producer, required fields and patterns.
 */
class RiskEventsAsyncApiContractTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void assessedEventMatchesTheContract() throws IOException {
        Map<String, Object> contract = load("svc-rsk-decisioning.yaml");
        Map<String, Object> envelopeSchemas = load("common/event-envelope.yaml");
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-CONTRACT-1", new BigDecimal("12000.50"), "AED", false, 0, "svc-pay-initiation-settlement"), 75,
            RiskDecision.REVIEW, List.of("HIGH_AMOUNT", "HIGH_RISK_COUNTRY"), "rsk-policy-v2");
        OutboxEventJpaEntity row = new RiskEventEnvelopeFactory(json)
            .toOutboxRow(assessment.getDomainEvents().getFirst(), "corr-contract", null);
        JsonNode envelope = json.readTree(row.getPayload());

        assertThat(row.getTopic()).isEqualTo(at(contract, "channels", "assessed", "address"));
        assertThat(envelope.get("eventType").asText())
            .isEqualTo(at(contract, "components", "messages", "RiskAssessed", "title"));
        List<Map<String, Object>> allOf = at(contract, "components", "messages", "RiskAssessed", "payload", "allOf");
        Map<String, Object> constants = at(allOf.get(1), "properties");
        assertThat(envelope.get("eventType").asText()).isEqualTo(at(constants, "eventType", "const"));
        assertThat(envelope.get("producer").asText()).isEqualTo(at(constants, "producer", "const"));

        Map<String, Object> envelopeSchema = at(envelopeSchemas, "EventEnvelope");
        for (String field : this.<List<String>>at(envelopeSchema, "required")) {
            assertThat(envelope.has(field)).as("envelope field %s", field).isTrue();
        }
        assertThat(envelope.get("eventType").asText()).matches(pattern(envelopeSchema, "eventType"));
        assertThat(envelope.get("producer").asText()).matches(pattern(envelopeSchema, "producer"));

        Map<String, Object> dataSchema = at(contract, "components", "schemas", "RiskAssessedData");
        JsonNode data = envelope.get("data");
        assertThat(data.fieldNames()).toIterable()
            .containsAll(this.<List<String>>at(dataSchema, "required"))
            .allMatch(field -> this.<Map<String, Object>>at(dataSchema, "properties").containsKey(field));
        // The spec stays 1.0.0 until it first lands on the catalog's main (governance change rules);
        // the optional attestationSource ships in 1.0.0, so no "since" history in descriptions.
        String version = at(contract, "info", "version");
        assertThat(version).isEqualTo("1.0.0");
        String address = at(contract, "channels", "assessed", "address");
        assertThat(address).as("topic suffix .vN matches the contract major")
            .endsWith(".v" + version.substring(0, version.indexOf('.')));
        assertThat(this.<String>at(dataSchema, "properties", "attestationSource", "description"))
            .doesNotContainIgnoringCase("since");
        assertThat(this.<List<String>>at(dataSchema, "required")).doesNotContain("attestationSource");
        assertThat(this.<List<String>>at(dataSchema, "properties", "attestationSource", "enum"))
            .contains(data.get("attestationSource").asText());
        assertThat(data.get("attestationSource").asText()).isEqualTo("CALLER_ATTESTED");
        assertThat(data.has("attestedBy")).as("the caller's client id stays in the API, not on the event").isFalse();
        assertThat(data.get("assessmentId").asText()).matches(pattern(dataSchema, "assessmentId"))
            .isEqualTo(envelope.get("aggregateId").asText());
        assertThat(this.<List<String>>at(dataSchema, "properties", "decision", "enum"))
            .contains(data.get("decision").asText());
        Map<String, Object> money = at(envelopeSchemas, "MonetaryAmount");
        assertThat(data.at("/amount/amount").asText()).matches(pattern(money, "amount"));
        assertThat(data.at("/amount/currency").asText()).matches(pattern(money, "currency"));
        String reasonPattern = at(dataSchema, "properties", "reasons", "items", "pattern");
        data.get("reasons").forEach(reason -> assertThat(reason.asText()).matches(reasonPattern));
    }

    private String pattern(Map<String, Object> schema, String property) {
        return at(schema, "properties", property, "pattern");
    }

    @SuppressWarnings("unchecked")
    private <T> T at(Map<String, Object> node, String... path) {
        Object current = node;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
            assertThat(current).as("contract path %s", String.join(".", path)).isNotNull();
        }
        return (T) current;
    }

    private static Map<String, Object> load(String relative) throws IOException {
        for (String prefix : List.of("", "../", "../../", "../../../")) {
            Path candidate = Path.of(prefix + "api/asyncapi/" + relative);
            if (Files.exists(candidate)) {
                return new Yaml().load(Files.readString(candidate));
            }
        }
        throw new IOException("Unable to locate api/asyncapi/" + relative);
    }
}
