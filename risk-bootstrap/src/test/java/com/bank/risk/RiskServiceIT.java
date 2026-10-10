package com.bank.risk;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: the test context migrates
 * sc_rsk_decisioning as the schema owner (what the chart's migration Job does;
 * the service itself only validates), Hibernate validates the entity against
 * it, and a payment service assesses transactions over HTTP as the runtime role.
 */
@SpringBootTest(properties = "risk.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class RiskServiceIT {

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        // As the migration owner: the runtime role the service connects as may not DELETE decisions of record.
        JdbcTemplate owner = PostgresTestDatabase.owner();
        owner.update("delete from sc_rsk_decisioning.outbox_event");
        owner.update("delete from sc_rsk_decisioning.risk_assessment");
    }

    /** V5: a decision of record always names its attestation source and who attested it. */
    @Test
    void theDatabaseRefusesADecisionWithoutAttesterOrAttestationSource() {
        String columns = "assessment_id, transaction_id, amount, currency, high_risk_country, velocity_score, score,"
            + " decision, reasons, assessed_at, rule_set_version, payment_type";
        String values = "'RISK-NULL-%s', 'TX-NULL-%s', 10.00, 'AED', false, 0, 0, 'ALLOW', '[]'::jsonb, now(), 'rsk-policy-v2', 'TRANSFER'";

        assertThatThrownBy(() -> jdbc.update("insert into sc_rsk_decisioning.risk_assessment (" + columns
                + ", attestation_source) values (" + values.formatted("1", "1") + ", 'CALLER_ATTESTED')"))
            .as("attested_by is NOT NULL")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
            .hasMessageContaining("attested_by");
        assertThatThrownBy(() -> jdbc.update("insert into sc_rsk_decisioning.risk_assessment (" + columns
                + ", attested_by) values (" + values.formatted("2", "2") + ", 'svc-pay-initiation-settlement')"))
            .as("attestation_source is NOT NULL, without a default")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
            .hasMessageContaining("attestation_source");
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.risk_assessment", Integer.class)).isZero();
    }

    @Test
    void flywayCreatesOnlyTheTablesThisServiceOwns() {
        List<String> tables = jdbc.queryForList("""
            select table_name from information_schema.tables
            where table_schema = 'sc_rsk_decisioning' and table_name <> 'flyway_schema_history'
            order by table_name
            """, String.class);

        assertThat(tables).containsExactly("legacy_credit_risk_assessment", "outbox_event", "risk_assessment");
    }

    /**
     * The service connects as a runtime role, not as the schema owner that
     * Flyway migrates with: it may read and insert decisions of record and
     * work the outbox, but not change or remove a decision, change the schema,
     * write the credit risk history or record a migration.
     */
    @Test
    void theRuntimeRoleCanOnlyReadAndInsertDecisionsAndWorkTheOutbox() throws Exception {
        assess("PAY-RUNTIME", "100.00", false, 0).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(PostgresTestDatabase.RUNTIME_ROLE);
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThatThrownBy(() -> runtime.update(
                "update sc_rsk_decisioning.risk_assessment set decision = 'ALLOW' where transaction_id = 'PAY-RUNTIME'"))
            .rootCause().hasMessageContaining("permission denied for table risk_assessment");
        assertThatThrownBy(() -> runtime.update(
                "delete from sc_rsk_decisioning.risk_assessment where transaction_id = 'PAY-RUNTIME'"))
            .rootCause().hasMessageContaining("permission denied for table risk_assessment");
        assertThatThrownBy(() -> runtime.execute("truncate sc_rsk_decisioning.risk_assessment"))
            .rootCause().hasMessageContaining("permission denied for table risk_assessment");
        assertThatThrownBy(() -> runtime.execute(
                "alter table sc_rsk_decisioning.risk_assessment drop constraint uq_risk_assessment_transaction"))
            .rootCause().hasMessageContaining("must be owner of table risk_assessment");
        assertThatThrownBy(() -> runtime.update(
                "insert into sc_rsk_decisioning.legacy_credit_risk_assessment (assessment_id) values ('RA-RUNTIME')"))
            .rootCause().hasMessageContaining("permission denied for table legacy_credit_risk_assessment");
        assertThatThrownBy(() -> runtime.execute("create table sc_rsk_decisioning.shadow (id int)"))
            .rootCause().hasMessageContaining("permission denied for schema sc_rsk_decisioning");
        // The service validates the schema history at startup, so it may read but never write it.
        assertThat(runtime.queryForObject(
                "select count(*) from sc_rsk_decisioning.flyway_schema_history where success", Integer.class))
            .isGreaterThanOrEqualTo(11);
        assertThatThrownBy(() -> runtime.update(
                "insert into sc_rsk_decisioning.flyway_schema_history (installed_rank, version, description, type,"
                    + " script, checksum, installed_by, execution_time, success)"
                    + " values (9999, '9999', 'forged', 'SQL', 'V9999__forged.sql', 0, current_user, 0, true)"))
            .rootCause().hasMessageContaining("permission denied for table flyway_schema_history");
        // The outbox is the relay's work queue: it marks, parks and purges rows.
        runtime.update("update sc_rsk_decisioning.outbox_event set attempts = attempts where aggregate_type = 'RiskAssessment'");
        assertThat(runtime.update("delete from sc_rsk_decisioning.outbox_event where published_at < now() - interval '1 year'"))
            .isZero();

        assertThat(runtime.queryForObject(
                "select decision from sc_rsk_decisioning.risk_assessment where transaction_id = 'PAY-RUNTIME'", String.class))
            .isEqualTo("ALLOW");
    }

    /** rsk-policy-v3: the payment type is a fact of the decision of record; a retry must repeat it. */
    @Test
    void thePaymentTypeIsStoredWithTheDecisionAndARetryWithAnotherTypeIsRefused() throws Exception {
        String mobile = """
            {"transactionId": "PAY-TYPE-1", "amount": 100.00, "currency": "USD", "highRiskCountry": false, "velocityScore": 0, "paymentType": "MOBILE_PAYMENT"}
            """;
        mvc.perform(asService(post("/api/v1/risk/assess")).contentType(MediaType.APPLICATION_JSON).content(mobile))
            .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select payment_type from sc_rsk_decisioning.risk_assessment"
            + " where transaction_id = 'PAY-TYPE-1'", String.class)).isEqualTo("MOBILE_PAYMENT");
        mvc.perform(asService(post("/api/v1/risk/assess")).contentType(MediaType.APPLICATION_JSON)
                .content(mobile.replace("MOBILE_PAYMENT", "TRANSFER")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_ASSESSED"));
    }

    @Test
    void assessmentIsStoredOnceAndReturnedOnRetry() throws Exception {
        String first = assess("PAY-RISK-1", "60000.00", true, 80)
            .andExpect(status().isCreated())
            .andExpect(header().string("x-fapi-interaction-id", "it-interaction-1"))
            .andExpect(jsonPath("$.decision").value("BLOCK"))
            .andExpect(jsonPath("$.score").value(100))
            .andExpect(jsonPath("$.ruleSetVersion").value("rsk-policy-v3"))
            .andReturn().getResponse().getContentAsString();
        String retry = assess("PAY-RISK-1", "60000", true, 80)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.risk_assessment", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reasons::text from sc_rsk_decisioning.risk_assessment", String.class))
            .contains("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "HIGH_VELOCITY");
        assertThat(jdbc.queryForObject("select rule_set_version from sc_rsk_decisioning.risk_assessment", String.class))
            .isEqualTo("rsk-policy-v3");
        assertThat(jdbc.queryForMap("select attestation_source, attested_by from sc_rsk_decisioning.risk_assessment"))
            .containsEntry("attestation_source", "CALLER_ATTESTED")
            .containsEntry("attested_by", "svc-pay-initiation-settlement");

        mvc.perform(asService(get("/api/v1/risk/assessments/{id}", "PAY-RISK-1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.decision").value("BLOCK"));
    }

    @Test
    void aStaffAssessmentIsAttestedByTheStaffMembersSubject() throws Exception {
        mvc.perform(post("/api/v1/risk/assess")
                .header("x-fapi-interaction-id", "it-interaction-1")
                .with(jwt().jwt(j -> j.subject("staff-1").claim("azp", "fintechbankx-web"))
                    .authorities(new SimpleGrantedAuthority("ROLE_BANKER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-RISK-STAFF", "10.00", false, 0)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.attestationSource").value("CALLER_ATTESTED"))
            .andExpect(jsonPath("$.attestedBy").value("staff-1"));

        assertThat(jdbc.queryForObject("select attested_by from sc_rsk_decisioning.risk_assessment "
            + "where transaction_id = 'PAY-RISK-STAFF'", String.class)).isEqualTo("staff-1");
    }

    @Test
    void reusedTransactionIdWithAnotherAmountIsRefused() throws Exception {
        assess("PAY-RISK-2", "100.00", false, 0).andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("ALLOW"));

        assess("PAY-RISK-2", "100.01", false, 0)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_ASSESSED"));
    }

    @Test
    void replayWithAnyDifferentDecisionInputIsRefused() throws Exception {
        assess("PAY-RISK-4", "100.00", false, 0).andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("ALLOW"));

        // Same id, amount and currency, but the caller now flags a high-risk country.
        assess("PAY-RISK-4", "100.00", true, 0)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_ASSESSED"));
        assess("PAY-RISK-4", "100.00", false, 45)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_ASSESSED"));
        assess("PAY-RISK-4", "100.0", false, 0).andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("ALLOW"));
    }

    @Test
    void unknownTransactionIsA404WithTheInteractionId() throws Exception {
        mvc.perform(asService(get("/api/v1/risk/assessments/{id}", "PAY-MISSING")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"))
            .andExpect(jsonPath("$.interactionId").value("it-interaction-1"));
    }

    @Test
    void onlyServicesAndStaffMayAssess() throws Exception {
        mvc.perform(post("/api/v1/risk/assess")
                .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-RISK-3", "10.00", false, 0)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risk/assessments/{id}", "PAY-ANY")).andExpect(status().isUnauthorized());
    }

    /** A client-credentials client that holds SERVICE but is not on SERVICE_CALLERS. */
    @Test
    void aServiceClientNotOnTheCallerListIsForbidden() throws Exception {
        mvc.perform(post("/api/v1/risk/assess")
                .with(jwt().jwt(j -> j.subject("service-account-customer").claim("azp", "svc-cus-profile-kyc"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAY-RISK-5", "10.00", false, 0)))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risk/assessments/{id}", "PAY-ANY")
                .with(jwt().jwt(j -> j.subject("service-account-customer").claim("azp", "svc-cus-profile-kyc"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/risk/assessments/{id}", "PAY-ANY")
                .with(jwt().jwt(j -> j.subject("staff-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER"))))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.risk_assessment", Integer.class)).isZero();
    }

    private ResultActions assess(String transactionId, String amount, boolean highRiskCountry, int velocity) throws Exception {
        return mvc.perform(asService(post("/api/v1/risk/assess"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(body(transactionId, amount, highRiskCountry, velocity)));
    }

    private static String body(String transactionId, String amount, boolean highRiskCountry, int velocity) {
        return """
            {"transactionId": "%s", "amount": %s, "currency": "USD", "highRiskCountry": %s, "velocityScore": %d, "paymentType": "TRANSFER"}
            """.formatted(transactionId, amount, highRiskCountry, velocity);
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request) {
        return request.header("x-fapi-interaction-id", "it-interaction-1")
            .with(jwt().jwt(j -> j.subject("service-account-payments").claim("azp", "svc-pay-initiation-settlement")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }
}
