package com.bank.risk.infrastructure.contract;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskContextOpenApiContractTest {

    @Test
    void shouldDefineImplementedRiskEndpoints() throws IOException {
        String spec = loadSpec();

        assertThat(spec).doesNotContain("paths: {}");
        assertThat(spec).contains("\n  /api/v1/risk/assess:\n");
        assertThat(spec).contains("\n  /api/v1/risk/assessments/{transactionId}:\n");
    }

    /**
     * Platform contract addendum 2026-10-08: DPoP binds open-finance TPP tokens only.
     * This service takes internal client-credentials and staff tokens, so the
     * contract must not promise a DPoP check the service does not make.
     */
    @Test
    @SuppressWarnings("unchecked")
    void theDpopHeaderIsOptionalAndSaysWhyThisServiceDoesNotVerifyIt() throws IOException {
        Map<String, Object> dpop = (Map<String, Object>) path(spec(), "components", "parameters", "DPoP");

        assertThat(dpop).containsEntry("name", "DPoP").containsEntry("in", "header").containsEntry("required", false);
        assertThat((String) dpop.get("description")).contains("TPP").contains("not verify");
        assertThat((Map<String, Object>) path(spec(), "components", "securitySchemes"))
            .as("no security scheme the service does not accept").doesNotContainKey("dpopAuth");
        for (String operation : List.of("/api/v1/risk/assess", "/api/v1/risk/assessments/{transactionId}")) {
            Map<String, Object> pathItem = (Map<String, Object>) path(spec(), "paths", operation);
            Map<String, Object> op = (Map<String, Object>) pathItem.values().iterator().next();
            assertThat((List<Map<String, Object>>) op.get("security"))
                .as("%s accepts a bearer token only; DPoP is neither required nor an alternative", operation)
                .containsExactly(Map.of("bearerAuth", List.of()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void caller_supplied_risk_facts_are_required_and_have_no_low_risk_default() throws IOException {
        Map<String, Object> request = (Map<String, Object>) path(spec(), "components", "schemas", "RiskAssessmentRequest");
        Map<String, Object> properties = (Map<String, Object>) request.get("properties");

        assertThat((List<String>) request.get("required")).contains("highRiskCountry", "velocityScore");
        assertThat((Map<String, Object>) properties.get("highRiskCountry")).doesNotContainKey("default");
        assertThat((Map<String, Object>) properties.get("velocityScore")).doesNotContainKey("default");
    }

    private static Map<String, Object> spec() throws IOException {
        return new Yaml().load(loadSpec());
    }

    @SuppressWarnings("unchecked")
    private static Object path(Map<String, Object> node, String... keys) {
        Object current = node;
        for (String key : keys) {
            current = ((Map<String, Object>) current).get(key);
        }
        return current;
    }

    private static String loadSpec() throws IOException {
        List<Path> candidates = List.of(
                Path.of("api/openapi/risk-context.yaml"),
                Path.of("../api/openapi/risk-context.yaml"),
                Path.of("../../api/openapi/risk-context.yaml"),
                Path.of("../../../api/openapi/risk-context.yaml")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }

        throw new IOException("Unable to locate risk-context.yaml");
    }
}
