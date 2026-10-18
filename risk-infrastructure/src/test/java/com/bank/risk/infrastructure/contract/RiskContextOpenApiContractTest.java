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
        for (String operation : List.of("/api/v1/risk/assess", "/api/v1/risk/assessments/{transactionId}",
                "/api/v2/risk/assess", "/api/v1/risk/loan-eligibility")) {
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

    /** rsk-policy-v3: the monolith's MOBILE_PAYMENT rule needs the payment type; it is required, with no default. */
    @Test
    @SuppressWarnings("unchecked")
    void paymentTypeIsRequiredWithTheMonolithsValuesAndNoDefault() throws IOException {
        Map<String, Object> request = (Map<String, Object>) path(spec(), "components", "schemas", "RiskAssessmentRequest");
        Map<String, Object> paymentType = (Map<String, Object>) ((Map<String, Object>) request.get("properties")).get("paymentType");

        assertThat((List<String>) request.get("required")).contains("paymentType");
        assertThat(paymentType).doesNotContainKey("default").containsEntry("type", "string");
        assertThat((List<String>) paymentType.get("enum")).containsExactly("TRANSFER", "WIRE_TRANSFER", "ACH", "CHECK",
                "CREDIT_CARD", "DEBIT_CARD", "MOBILE_PAYMENT", "LOAN_PAYMENT", "BILL_PAYMENT");
    }

    /** Screening-facts plan, "Contract approach": v2 takes ids and amounts only; v1 stays, deprecated, behind a flag. */
    @Test
    @SuppressWarnings("unchecked")
    void v2AssessTakesIdsAndAmountsOnlyAndV1IsDeprecated() throws IOException {
        Map<String, Object> spec = spec();
        Map<String, Object> v1 = (Map<String, Object>) path(spec, "paths", "/api/v1/risk/assess", "post");
        assertThat(v1).containsEntry("deprecated", true);
        assertThat((String) v1.get("description")).contains("RISK_V1_CALLER_FACTS_ENABLED").contains("/api/v2/risk/assess");
        assertThat((Map<String, Object>) v1.get("responses")).as("the flag-off answer is documented").containsKey("410");

        Map<String, Object> v2 = (Map<String, Object>) path(spec, "paths", "/api/v2/risk/assess", "post");
        assertThat(v2).doesNotContainEntry("deprecated", true);
        assertThat((List<Map<String, Object>>) v2.get("security")).containsExactly(Map.of("bearerAuth", List.of()));
        assertThat((Map<String, Object>) v2.get("responses")).containsKeys("201", "400", "409", "503");
        String requestRef = (String) path(v2, "requestBody", "content", "application/json", "schema", "$ref");
        Map<String, Object> request = (Map<String, Object>) path(spec, "components", "schemas", requestRef.substring(requestRef.lastIndexOf('/') + 1));
        assertThat((List<String>) request.get("required"))
                .containsExactlyInAnyOrder("transactionId", "customerId", "amount", "currency", "paymentType", "counterpartyAccount");
        assertThat((Map<String, Object>) request.get("properties")).doesNotContainKeys("highRiskCountry", "velocityScore");
        assertThat(request).as("a caller-stated fact is refused, so the schema is closed").containsEntry("additionalProperties", false);
        assertThat((String) path(v2, "responses", "503", "description")).contains("RISK_SOURCE_UNAVAILABLE");

        Map<String, Object> response = (Map<String, Object>) path(spec, "components", "schemas", "RiskAssessmentResponse", "properties");
        assertThat(response).containsKeys("customerId", "counterpartyCountry");
        assertThat((String) path(response, "attestationSource", "description")).contains("RISK_RESOLVED");
    }

    /** "Risk decides" loan eligibility: the loan service asks risk, which reads the customer's credit profile. */
    @Test
    @SuppressWarnings("unchecked")
    void loanEligibilityIsAnsweredByRisk() throws IOException {
        Map<String, Object> spec = spec();
        Map<String, Object> op = (Map<String, Object>) path(spec, "paths", "/api/v1/risk/loan-eligibility", "post");

        assertThat((List<Map<String, Object>>) op.get("security")).containsExactly(Map.of("bearerAuth", List.of()));
        assertThat((Map<String, Object>) op.get("responses")).containsKeys("200", "400", "404", "503");
        assertThat((String) path(op, "responses", "503", "description")).contains("CUSTOMER_SERVICE_UNAVAILABLE");
        Map<String, Object> request = (Map<String, Object>) path(spec, "components", "schemas", "LoanEligibilityRequest");
        assertThat((List<String>) request.get("required")).containsExactlyInAnyOrder("customerId", "requestedAmount", "currency");
        Map<String, Object> response = (Map<String, Object>) path(spec, "components", "schemas", "LoanEligibilityResponse", "properties");
        assertThat((List<String>) path(response, "decision", "enum")).containsExactly("ELIGIBLE", "INELIGIBLE");
        assertThat((String) path(response, "reason", "description")).contains("CREDIT_SCORE_BELOW_MINIMUM").contains("600");
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
