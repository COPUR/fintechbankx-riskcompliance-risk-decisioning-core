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
// The test database has no TLS: fintechbankx.tls.enforce=false is a test-only switch (StartupTlsAssertionsTest).
// v1 assess is deprecated and off by default (screening-facts plan): these v1 tests switch it on. The home country is
// what RISK_HOME_COUNTRY would be set to in an environment.
// risk.v1.caller-facts-enabled is left at the application default (true): v1 keeps answering until the
// operator turns it off after payments moves to v2 (S5); RiskV1RetiredIT covers the "false" side.
@SpringBootTest(properties = {"risk.outbox.relay.enabled=false", "fintechbankx.tls.enforce=false",
    "risk.country.home=AE", "risk.velocity.window=PT24H"})
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
    @Autowired com.bank.risk.domain.port.in.TransactionAssessmentUseCase assessments;

    /** The shape payments' AccountId generates: "ACC-" + UUID (payment-domain AccountId.java:26). */
    private static final String INTERNAL_ACCOUNT = "ACC-9b2f6c1e-4d3a-4f8b-9c7d-2e1f0a9b8c7d";

    @BeforeEach
    void cleanTables() {
        // As the migration owner: the runtime role the service connects as may not DELETE decisions of record.
        JdbcTemplate owner = PostgresTestDatabase.owner();
        owner.update("delete from sc_rsk_decisioning.outbox_event");
        owner.update("delete from sc_rsk_decisioning.risk_assessment");
        owner.update("delete from sc_rsk_decisioning.high_risk_country");
    }

    /** What scripts/ops/load-high-risk-countries.sh writes: one loaded_at per load, as the owner. */
    private static void loadHighRiskCountries(String loadedAt, String... codes) {
        JdbcTemplate owner = PostgresTestDatabase.owner();
        for (String code : codes) {
            owner.update("insert into sc_rsk_decisioning.high_risk_country (country_code, list_source, list_version, loaded_at)"
                + " values (?, 'FATF', '2026-06', " + loadedAt + ")", code);
        }
    }

    private static String v2Body(String transactionId, String customerId, String amount, String account) {
        return """
            {"transactionId": "%s", "customerId": "%s", "amount": %s, "currency": "USD", "paymentType": "TRANSFER", "counterpartyAccount": "%s"}
            """.formatted(transactionId, customerId, amount, account);
    }

    private ResultActions assessV2(String transactionId, String customerId, String amount, String account) throws Exception {
        return mvc.perform(asService(post("/api/v2/risk/assess")).contentType(MediaType.APPLICATION_JSON)
            .content(v2Body(transactionId, customerId, amount, account)));
    }

    /**
     * v2: the caller sends ids and amounts; risk resolves the counterparty country from the IBAN against its own
     * list and the velocity from its own decisions of record for the customer, and stores both facts (V13).
     */
    @Test
    void v2ResolvesTheCountryAndVelocityFromRisksOwnSourcesAndStoresThem() throws Exception {
        loadHighRiskCountries("now()", "IR", "KP");

        // 12000.50 USD: HIGH_AMOUNT (30) and the resolved HIGH_RISK_COUNTRY (40) give 70, REVIEW; not a round amount.
        assessV2("PAY-V2-IR", "CUST-V2-A", "12000.50", "IR580540105180021273113007")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("REVIEW"))
            .andExpect(jsonPath("$.score").value(70))
            .andExpect(jsonPath("$.reasons").value(org.hamcrest.Matchers.containsInAnyOrder("HIGH_AMOUNT", "HIGH_RISK_COUNTRY")))
            .andExpect(jsonPath("$.attestationSource").value("RISK_RESOLVED"))
            .andExpect(jsonPath("$.attestedBy").value("svc-pay-initiation-settlement"))
            .andExpect(jsonPath("$.customerId").value("CUST-V2-A"))
            .andExpect(jsonPath("$.counterpartyCountry").value("IR"))
            .andExpect(jsonPath("$.ruleSetVersion").value("rsk-policy-v4"));
        assertThat(jdbc.queryForMap("select customer_id, counterparty_country, high_risk_country, velocity_score, attestation_source"
                + " from sc_rsk_decisioning.risk_assessment where transaction_id = 'PAY-V2-IR'"))
            .containsEntry("customer_id", "CUST-V2-A")
            .containsEntry("counterparty_country", "IR")
            .containsEntry("high_risk_country", true)
            .containsEntry("velocity_score", 0)
            .containsEntry("attestation_source", "RISK_RESOLVED");

        // Four decisions for CUST-V2-B in the window, then the fifth: 4 prior assessments map to velocityScore 40.
        for (int i = 1; i <= 4; i++) {
            assessV2("PAY-V2-B" + i, "CUST-V2-B", "100.00", "GB82WEST12345698765432")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.counterpartyCountry").value("GB"));
        }
        // The fifth names the customer with surrounding blanks: the id is canonical (trimmed, as customer's
        // CustomerId.of does), so it is the same customer for velocity and is stored trimmed (S-2).
        assessV2("PAY-V2-B5", " CUST-V2-B ", "100.00", INTERNAL_ACCOUNT)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reasons").value(org.hamcrest.Matchers.contains("MEDIUM_VELOCITY")))
            .andExpect(jsonPath("$.customerId").value("CUST-V2-B"))
            .andExpect(jsonPath("$.counterpartyCountry").value("AE"));
        assertThat(jdbc.queryForMap("select velocity_score, customer_id from sc_rsk_decisioning.risk_assessment"
            + " where transaction_id = 'PAY-V2-B5'"))
            .containsEntry("velocity_score", 40).containsEntry("customer_id", "CUST-V2-B");
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.outbox_event", Integer.class))
            .as("one event per decision").isEqualTo(6);

        // A faithful retry (same ids, amount by value, same account) gets the stored decision; another account is refused.
        String first = assessV2("PAY-V2-B5", "CUST-V2-B", "100.00", INTERNAL_ACCOUNT).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String retry = assessV2("PAY-V2-B5", "CUST-V2-B", "100.0", INTERNAL_ACCOUNT).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        assertThat(retry).isEqualTo(first);
        assessV2("PAY-V2-B5", "CUST-V2-B", "100.00", "GB82WEST12345698765432")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_ASSESSED"));
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.risk_assessment", Integer.class)).isEqualTo(6);
    }

    /** No list, or a list older than RISK_COUNTRY_LIST_MAX_AGE (P35D): 503, and no decision of record. */
    @Test
    void v2RefusesToDecideWithoutAFreshHighRiskCountryList() throws Exception {
        assessV2("PAY-V2-EMPTY", "CUST-V2-C", "100.00", "GB82WEST12345698765432")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("RISK_SOURCE_UNAVAILABLE"))
            .andExpect(jsonPath("$.interactionId").value("it-interaction-1"));

        loadHighRiskCountries("now() - interval '36 days'", "IR");
        assessV2("PAY-V2-STALE", "CUST-V2-C", "100.00", "GB82WEST12345698765432")
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("RISK_SOURCE_UNAVAILABLE"));

        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.risk_assessment", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.outbox_event", Integer.class)).isZero();
    }

    /**
     * An account that is neither an IBAN of a registered country nor an internal account has no country: high
     * risk, stored without a country. ZZ passes mod-97 and is not a country (B-1); "ACC-" in front of an Iranian
     * IBAN is not an internal account (S-1).
     */
    @Test
    void v2TreatsAnUnresolvableCounterpartyAsHighRisk() throws Exception {
        loadHighRiskCountries("now()", "IR");

        int n = 0;
        for (String account : List.of("wallet:0xabc", "ZZ1812345678901234", "ACC-IR580540105180021273113007", "ACC-000123")) {
            String transactionId = "PAY-V2-UNKNOWN-" + (++n);
            assessV2(transactionId, "CUST-V2-D" + n, "100.00", account)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reasons").value(org.hamcrest.Matchers.contains("HIGH_RISK_COUNTRY")))
                .andExpect(jsonPath("$.counterpartyCountry").doesNotExist());
            assertThat(jdbc.queryForObject("select counterparty_country from sc_rsk_decisioning.risk_assessment"
                + " where transaction_id = ?", String.class, transactionId)).as(account).isNull();
        }
    }

    /**
     * Velocity cannot be gamed by firing payments at once (S-2): the count and the insert run under a
     * per-customer transaction-scoped advisory lock, so N parallel assessments of one customer see
     * 0, 1, ..., N-1 prior decisions, never the same count twice.
     */
    @Test
    void parallelAssessmentsOfOneCustomerSeeIncreasingVelocity() throws Exception {
        loadHighRiskCountries("now()", "IR");
        int parallel = 6;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(parallel);
        try {
            List<java.util.concurrent.Future<Integer>> scores = new java.util.ArrayList<>();
            for (int i = 1; i <= parallel; i++) {
                String transactionId = "PAY-V2-PAR-" + i;
                scores.add(pool.submit(() -> {
                    start.await();
                    return assessments.assess(new com.bank.risk.domain.port.in.TransactionAssessmentCommand(transactionId,
                        "CUST-V2-PAR", new java.math.BigDecimal("100.00"), "USD", com.bank.risk.domain.PaymentType.TRANSFER,
                        "GB82WEST12345698765432", "svc-pay-initiation-settlement")).getVelocityScore();
                }));
            }
            start.countDown();
            List<Integer> seen = new java.util.ArrayList<>();
            for (var score : scores) {
                seen.add(score.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(seen).containsExactlyInAnyOrder(0, 10, 20, 30, 40, 50);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForList("select velocity_score from sc_rsk_decisioning.risk_assessment"
            + " where customer_id = 'CUST-V2-PAR' order by velocity_score", Integer.class))
            .containsExactly(0, 10, 20, 30, 40, 50);
    }

    /** No token is 401; a token with the wrong role, or a SERVICE token whose client is not listed, is 403 (S4). */
    @Test
    void v2AssessAndLoanEligibilityRefuseMissingWrongAndUnlistedCallers() throws Exception {
        loadHighRiskCountries("now()", "IR");
        String eligibility = """
            {"customerId": "CUST-ELIG-2", "requestedAmount": 5000.00, "currency": "AED"}
            """;
        mvc.perform(post("/api/v2/risk/assess").contentType(MediaType.APPLICATION_JSON)
                .content(v2Body("PAY-V2-ANON", "CUST-ANON", "100.00", "GB82WEST12345698765432")))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/risk/loan-eligibility").contentType(MediaType.APPLICATION_JSON).content(eligibility))
            .andExpect(status().isUnauthorized());

        for (String role : List.of("ROLE_AUDITOR", "ROLE_CUSTOMER")) {
            mvc.perform(post("/api/v2/risk/assess")
                    .with(jwt().jwt(j -> j.subject("user-1").claim("azp", "fintechbankx-web")).authorities(new SimpleGrantedAuthority(role)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(v2Body("PAY-V2-ROLE", "CUST-ROLE", "100.00", "GB82WEST12345698765432")))
                .andExpect(status().isForbidden());
        }
        for (String role : List.of("ROLE_AUDITOR", "ROLE_BANKER", "ROLE_ADMIN")) {
            mvc.perform(post("/api/v1/risk/loan-eligibility")
                    .with(jwt().jwt(j -> j.subject("staff-1").claim("azp", "fintechbankx-web")).authorities(new SimpleGrantedAuthority(role)))
                    .contentType(MediaType.APPLICATION_JSON).content(eligibility))
                .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/v2/risk/assess")
                .with(jwt().jwt(j -> j.subject("service-account-customer").claim("azp", "svc-cus-profile-kyc"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(v2Body("PAY-V2-UNLISTED", "CUST-UNLISTED", "100.00", "GB82WEST12345698765432")))
            .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.risk_assessment", Integer.class))
            .as("nothing was decided for a refused caller").isZero();
    }

    /** V12: the service reads the list; only the operator script, as the owner, loads it. */
    @Test
    void theRuntimeRoleReadsButCannotLoadTheHighRiskCountryList() {
        loadHighRiskCountries("now()", "IR");
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThat(runtime.queryForObject("select count(*) from sc_rsk_decisioning.high_risk_country", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> runtime.update("insert into sc_rsk_decisioning.high_risk_country values ('KP', 'x', 'y', now())"))
            .rootCause().hasMessageContaining("permission denied for table high_risk_country");
        assertThatThrownBy(() -> runtime.update("delete from sc_rsk_decisioning.high_risk_country"))
            .rootCause().hasMessageContaining("permission denied for table high_risk_country");
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

        assertThat(tables).containsExactly("high_risk_country", "legacy_credit_risk_assessment", "outbox_event", "risk_assessment");
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
            .isGreaterThanOrEqualTo(13);
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
            .andExpect(jsonPath("$.ruleSetVersion").value("rsk-policy-v4"))
            .andReturn().getResponse().getContentAsString();
        String retry = assess("PAY-RISK-1", "60000", true, 80)
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from sc_rsk_decisioning.risk_assessment", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reasons::text from sc_rsk_decisioning.risk_assessment", String.class))
            .contains("HIGH_AMOUNT", "VERY_HIGH_AMOUNT", "HIGH_RISK_COUNTRY", "HIGH_VELOCITY");
        assertThat(jdbc.queryForObject("select rule_set_version from sc_rsk_decisioning.risk_assessment", String.class))
            .isEqualTo("rsk-policy-v4");
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

    /**
     * Loan eligibility has its own caller list (SERVICE_CALLERS_ELIGIBILITY, default svc-ln-loan-lifecycle): the
     * service's own client, a service on SERVICE_CALLERS and an unlisted service all get 403. The listed loan
     * service passes authorisation; without an identity client credential in the test it then gets the
     * customer-unavailable 503, never a decision.
     */
    @Test
    void loanEligibilityAdmitsOnlyTheListedLoanService() throws Exception {
        String body = """
            {"customerId": "CUST-ELIG-1", "requestedAmount": 5000.00, "currency": "AED"}
            """;
        for (String azp : List.of("svc-rsk-decisioning", "svc-pay-initiation-settlement", "svc-cus-profile-kyc")) {
            mvc.perform(post("/api/v1/risk/loan-eligibility")
                    .with(jwt().jwt(j -> j.subject("service-account-" + azp).claim("azp", azp))
                        .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                .andExpect(status().isForbidden());
        }
        // Staff roles do not open eligibility either: service callers only (S-4).
        mvc.perform(post("/api/v1/risk/loan-eligibility")
                .with(jwt().jwt(j -> j.subject("staff-1").claim("azp", "fintechbankx-web")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/risk/loan-eligibility")
                .with(jwt().jwt(j -> j.subject("service-account-loan").claim("azp", "svc-ln-loan-lifecycle"))
                    .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CUSTOMER_SERVICE_UNAVAILABLE"));
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
