package com.bank.risk;

import com.bank.risk.application.RiskAssessmentService;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.port.in.RiskEvaluationCommand;
import com.bank.risk.domain.port.in.RiskAssessmentUseCase;
import com.bank.risk.domain.port.out.RiskAssessmentRepository;
import com.bank.risk.domain.port.out.RiskEventPublisher;
import com.bank.risk.domain.service.RiskPolicyService;
import com.bank.risk.infrastructure.outbox.OutboxRelay;
import com.bank.risk.infrastructure.outbox.SpringDataOutboxRepository;
import com.bank.risk.infrastructure.transaction.TransactionalRiskAssessmentUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Transactional outbox against PostgreSQL: an assessment and its
 * Risk.RiskAssessment.Assessed.v1 row commit together, retries add nothing,
 * and a lost insert race or a failed outbox write leaves neither behind.
 * The scheduled relay is off; Kafka is mocked.
 */
@SpringBootTest(properties = "risk.outbox.relay.enabled=false")
@AutoConfigureMockMvc
class RiskOutboxIT {

    private static final String OUTBOX = "sc_rsk_decisioning.outbox_event";
    private static final String ASSESSMENTS = "sc_rsk_decisioning.risk_assessment";
    private static final ExecutorService LOSER = Executors.newSingleThreadExecutor();

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @AfterAll
    static void stopExecutor() {
        LOSER.shutdownNow();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired ObjectMapper json;
    @Autowired RiskAssessmentRepository repository;
    @Autowired RiskEventPublisher publisher;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;

    @MockBean KafkaTemplate<String, String> kafka;

    @BeforeEach
    void cleanTables() {
        jdbc.update("delete from " + OUTBOX);
        jdbc.update("delete from " + ASSESSMENTS);
    }

    @Test
    void aNewAssessmentWritesExactlyOneOutboxRowAndARetryWritesNone() throws Exception {
        String response = assess("PAY-OUT-1", "60000.00").andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String assessmentId = json.readTree(response).get("assessmentId").asText();

        List<Map<String, Object>> rows = jdbc.queryForList(
            "select topic, event_type, aggregate_type, aggregate_id, aggregate_version, correlation_id, published_at from " + OUTBOX);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("topic")).isEqualTo("evt.rsk.risk.assessed.v1");
        assertThat(row.get("event_type")).isEqualTo("Risk.RiskAssessment.Assessed.v1");
        assertThat(row.get("aggregate_type")).isEqualTo("RiskAssessment");
        assertThat(row.get("aggregate_id")).isEqualTo(assessmentId);
        assertThat(row.get("aggregate_version")).isEqualTo(0L);
        assertThat(row.get("correlation_id")).isEqualTo("it-outbox-1");
        assertThat(row.get("published_at")).isNull();
        assertThat(jdbc.queryForObject("select traceparent from " + OUTBOX, String.class))
            .as("no tracing bridge is active in this test, so no trace context is invented").isNull();

        JsonNode envelope = json.readTree(jdbc.queryForObject("select payload::text from " + OUTBOX, String.class));
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-rsk-decisioning");
        assertThat(envelope.at("/data/transactionId").asText()).isEqualTo("PAY-OUT-1");
        assertThat(envelope.at("/data/decision").asText()).isEqualTo("BLOCK");
        assertThat(envelope.at("/data/amount/amount").asText()).isEqualTo("60000.00");

        assess("PAY-OUT-1", "60000").andExpect(status().isCreated());
        assess("PAY-OUT-1", "60000.01").andExpect(status().isConflict());

        assertThat(count(OUTBOX)).isEqualTo(1);
        assertThat(count(ASSESSMENTS)).isEqualTo(1);
    }

    @Test
    void anAssessmentThatLosesTheInsertRaceIsARetryableConflictAndLeavesNoOutboxRow() throws Exception {
        Future<MvcResult> loser;
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (Statement insert = winner.createStatement()) {
                insert.executeUpdate("insert into " + ASSESSMENTS + " (assessment_id, transaction_id, amount, currency, "
                    + "high_risk_country, velocity_score, score, decision, reasons, assessed_at) values ('RISK-RACE-WINNER', "
                    + "'PAY-RACE-1', 100.00, 'AED', true, 80, 70, 'REVIEW', '[\"HIGH_RISK_COUNTRY\",\"HIGH_VELOCITY\"]'::jsonb, now())");
            }
            // The loser cannot see the uncommitted winner, evaluates, and blocks on the unique index.
            loser = LOSER.submit(() -> assess("PAY-RACE-1", "100.00").andReturn());
            awaitLockWait();
            winner.commit();
        }

        MvcResult lost = loser.get(15, TimeUnit.SECONDS);
        assertThat(lost.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(lost.getResponse().getContentAsString()).get("code").asText()).isEqualTo("DUPLICATE_REQUEST");
        assertThat(count(OUTBOX)).isZero();
        assertThat(jdbc.queryForList("select assessment_id from " + ASSESSMENTS, String.class))
            .containsExactly("RISK-RACE-WINNER");

        // The retry the 409 invites returns the winner's decision.
        assess("PAY-RACE-1", "100.00").andExpect(status().isCreated())
            .andExpect(jsonPath("$.assessmentId").value("RISK-RACE-WINNER"));
        assertThat(count(OUTBOX)).isZero();
    }

    @Test
    void aFailedOutboxWriteRollsTheAssessmentBack() {
        RiskEventPublisher failingAfterWrite = events -> {
            publisher.publish(events);
            throw new IllegalStateException("outbox write failed");
        };
        RiskAssessmentUseCase withFailingOutbox = new TransactionalRiskAssessmentUseCase(
            new RiskAssessmentService(new RiskPolicyService(), repository, failingAfterWrite),
            new TransactionTemplate(transactionManager), new TransactionTemplate(transactionManager));

        assertThatThrownBy(() -> withFailingOutbox.assess(
            new RiskEvaluationCommand("PAY-ROLLBACK-1", new BigDecimal("15000.00"), "AED", true, 0)))
            .isInstanceOf(IllegalStateException.class);

        assertThat(count(ASSESSMENTS)).isZero();
        assertThat(count(OUTBOX)).isZero();
    }

    @Test
    void theOutboxRefusesToWriteOutsideATransaction() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-NO-TX", new BigDecimal("1.00"), "AED", false, 0), 0,
            RiskDecision.ALLOW, List.of());

        assertThatThrownBy(() -> publisher.publish(assessment.getDomainEvents()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count(OUTBOX)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRelayPublishesPendingRowsKeyedByAssessmentAndMarksThem() throws Exception {
        String assessmentId = json.readTree(assess("PAY-RELAY-1", "250.00").andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString()).get("assessmentId").asText();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 10, Duration.ofSeconds(1), Duration.ofDays(7), 10);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).isZero();

        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(records.capture());
        assertThat(records.getValue().topic()).isEqualTo("evt.rsk.risk.assessed.v1");
        assertThat(records.getValue().key()).isEqualTo(assessmentId);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        assertThat(relay.purgePublished()).isZero();
        assertThat(count(OUTBOX)).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRequestTraceIsStoredWithTheEventAndSentAsTraceparent() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        RiskAssessment assessment = RiskAssessment.create(
            new RiskEvaluationCommand("PAY-TRACE-1", new BigDecimal("20.00"), "AED", false, 0), 0, RiskDecision.ALLOW, List.of());
        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                publisher.publish(assessment.getDomainEvents()));
        } finally {
            MDC.clear();
        }
        assertThat(jdbc.queryForObject("select traceparent from " + OUTBOX, String.class)).isEqualTo(traceparent);

        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(), 10,
            Duration.ofSeconds(1), Duration.ofDays(7), 10).relayOnce();

        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka).send(records.capture());
        assertThat(new String(records.getValue().headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
            .isEqualTo(traceparent);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aRowThatCanNeverBeSentIsParkedSkippedAndCountedWhileLaterRowsArePublished() throws Exception {
        assess("PAY-PARK-1", "10.00").andExpect(status().isCreated());
        assess("PAY-PARK-2", "20.00").andExpect(status().isCreated());
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaProducerException(
                new ProducerRecord<>("evt.rsk.risk.assessed.v1", "k", "v"), "Failed to send",
                new RecordTooLargeException("too large"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 10, Duration.ofSeconds(1), Duration.ofDays(7), 10);

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).as("the parked row is not picked up again").isZero();

        Mockito.verify(kafka, Mockito.times(2)).send(any(ProducerRecord.class));
        Map<String, Object> parked = jdbc.queryForMap("select o.parked_at, o.last_error, o.attempts from " + OUTBOX
            + " o where o.payload -> 'data' ->> 'transactionId' = 'PAY-PARK-1'");
        assertThat(parked.get("parked_at")).isNotNull();
        assertThat((String) parked.get("last_error")).startsWith("RecordTooLargeException");
        assertThat(parked.get("attempts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + OUTBOX
            + " where published_at is not null and payload -> 'data' ->> 'transactionId' = 'PAY-PARK-2'", Integer.class))
            .isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNotNull()).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).isZero();

        // Manual replay (runbook): un-park and reset the attempts; the next run publishes it.
        jdbc.update("update " + OUTBOX + " set parked_at = null, attempts = 0, last_error = null where parked_at is not null");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    private void awaitLockWait() throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity "
                + "where datname = current_database() and wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The second assessment never waited on the unique transaction_id index");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private ResultActions assess(String transactionId, String amount) throws Exception {
        return mvc.perform(post("/api/v1/risk/assess")
            .header("x-fapi-interaction-id", "it-outbox-1")
            .with(jwt().jwt(j -> j.subject("service-account-payments").claim("azp", "svc-pay-initiation-settlement")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"transactionId": "%s", "amount": %s, "currency": "AED", "highRiskCountry": true, "velocityScore": 80}
                """.formatted(transactionId, amount)));
    }
}
