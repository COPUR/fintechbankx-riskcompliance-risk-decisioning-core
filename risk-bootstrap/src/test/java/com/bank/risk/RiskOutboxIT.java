package com.bank.risk;

import com.bank.risk.application.RiskAssessmentService;
import com.bank.risk.domain.RiskAssessment;
import com.bank.risk.domain.RiskDecision;
import com.bank.risk.domain.command.RiskEvaluationCommand;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
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
    @Autowired RiskAssessmentUseCase useCase;
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
    void anAssessmentThatLosesTheInsertRaceLeavesNoOutboxRow() throws Exception {
        RiskEvaluationCommand command = new RiskEvaluationCommand("PAY-RACE-1", new BigDecimal("100.00"), "AED", false, 0);
        Future<Throwable> loser;
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            try (Statement insert = winner.createStatement()) {
                insert.executeUpdate("insert into " + ASSESSMENTS + " (assessment_id, transaction_id, amount, currency, score, "
                    + "decision, reasons, assessed_at) values ('RISK-RACE-WINNER', 'PAY-RACE-1', 100.00, 'AED', 0, 'ALLOW', "
                    + "'[]'::jsonb, now())");
            }
            // The loser does not see the uncommitted winner, evaluates, and blocks on the unique index.
            loser = LOSER.submit(() -> {
                try {
                    useCase.assess(command);
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            awaitLockWait();
            winner.commit();
        }

        assertThat(loser.get(15, TimeUnit.SECONDS)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count(OUTBOX)).isZero();
        assertThat(jdbc.queryForList("select assessment_id from " + ASSESSMENTS, String.class))
            .containsExactly("RISK-RACE-WINNER");
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
        RiskAssessment assessment = RiskAssessment.create("PAY-NO-TX", new BigDecimal("1.00"), "AED", 0,
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
            Clock.systemUTC(), 10, Duration.ofSeconds(1), Duration.ofDays(7));

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
            .with(jwt().jwt(j -> j.subject("svc-pay-initiation-settlement")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"transactionId": "%s", "amount": %s, "currency": "AED", "highRiskCountry": true, "velocityScore": 80}
                """.formatted(transactionId, amount)));
    }
}
