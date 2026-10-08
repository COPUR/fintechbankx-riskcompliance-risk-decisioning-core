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
import java.util.concurrent.CountDownLatch;
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
                    + "high_risk_country, velocity_score, score, decision, reasons, assessed_at, attestation_source, attested_by, rule_set_version) "
                    + "values ('RISK-RACE-WINNER', 'PAY-RACE-1', 100.00, 'USD', true, 80, 70, 'REVIEW', "
                    + "'[\"HIGH_RISK_COUNTRY\",\"HIGH_VELOCITY\"]'::jsonb, now(), 'CALLER_ATTESTED', 'svc-pay-initiation-settlement', 'rsk-policy-v2')");
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
            new RiskEvaluationCommand("PAY-ROLLBACK-1", new BigDecimal("15000.00"), "AED", true, 0, "svc-pay-initiation-settlement")))
            .isInstanceOf(IllegalStateException.class);

        assertThat(count(ASSESSMENTS)).isZero();
        assertThat(count(OUTBOX)).isZero();
    }

    @Test
    void theOutboxRefusesToWriteOutsideATransaction() {
        RiskAssessment assessment = RiskAssessment.create(new RiskEvaluationCommand("PAY-NO-TX", new BigDecimal("1.00"), "AED", false, 0, "svc-pay-initiation-settlement"), 0,
            RiskDecision.ALLOW, List.of(), "rsk-policy-v2");

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
            Clock.systemUTC(), 10, Duration.ofSeconds(1), Duration.ofDays(7),
            Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

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
            new RiskEvaluationCommand("PAY-TRACE-1", new BigDecimal("20.00"), "AED", false, 0, "svc-pay-initiation-settlement"), 0, RiskDecision.ALLOW, List.of(), "rsk-policy-v2");
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
            Duration.ofSeconds(1), Duration.ofDays(7),
            Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).relayOnce();

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
            Clock.systemUTC(), 10, Duration.ofSeconds(1), Duration.ofDays(7),
            Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.relayOnce()).as("the parked row is not picked up again").isZero();

        Mockito.verify(kafka, Mockito.times(2)).send(any(ProducerRecord.class));
        Map<String, Object> parked = jdbc.queryForMap("select o.parked_at, o.first_failed_at, o.last_error, o.attempts from " + OUTBOX
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
        jdbc.update("update " + OUTBOX + " set parked_at = null, park_counted = false, first_failed_at = null, attempts = 0, last_error = null"
            + " where parked_at is not null");
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    /**
     * Platform ruling: outbox.parked.events counts each parked row once. A
     * relay park is written with park_counted = true; an operator park (the
     * runbook UPDATE) is counted by the next tick, as OperatorPark, and never
     * again by any replica.
     */
    @Test
    @SuppressWarnings("unchecked")
    void eachParkedRowIsCountedOnceWhetherTheRelayOrAnOperatorParkedIt() throws Exception {
        assess("PAY-CNT-1", "10.00").andExpect(status().isCreated());
        assess("PAY-CNT-2", "20.00").andExpect(status().isCreated());
        assess("PAY-CNT-3", "30.00").andExpect(status().isCreated());
        // Runbook "Manual park", verbatim apart from the placeholders.
        jdbc.update("UPDATE " + OUTBOX + " SET parked_at = now(), last_error = left('manual: test, CHG-1', 512)"
            + " WHERE event_id = (select event_id from " + OUTBOX
            + " where payload -> 'data' ->> 'transactionId' = 'PAY-CNT-1') AND published_at IS NULL AND parked_at IS NULL");
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new KafkaProducerException(
                new ProducerRecord<>("evt.rsk.risk.assessed.v1", "k", "v"), "Failed to send",
                new RecordTooLargeException("too large"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 10, Duration.ofSeconds(1), Duration.ofDays(7),
            Duration.ofSeconds(1), Duration.ofMinutes(5), metrics);

        assertThat(relay.relayOnce()).isEqualTo(1);
        relay.relayOnce();
        relay.relayOnce();

        assertThat(metrics.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count()).isEqualTo(1.0);
        assertThat(metrics.get("outbox.parked.events").tag("exception", "RecordTooLargeException").counter().count())
            .isEqualTo(1.0);
        assertThat(jdbc.queryForObject("select count(*) from " + OUTBOX
            + " where parked_at is not null and park_counted", Integer.class)).isEqualTo(2);

        io.micrometer.core.instrument.simple.SimpleMeterRegistry otherReplica =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), Clock.systemUTC(), 10,
            Duration.ofSeconds(1), Duration.ofDays(7), Duration.ofSeconds(1), Duration.ofMinutes(5), otherReplica)
            .relayOnce();
        assertThat(otherReplica.get("outbox.parked.events").counters())
            .as("another replica or a restart never counts a parked row again")
            .allMatch(counter -> counter.count() == 0.0);
    }

    @Test
    void theOldestPendingAgeIsZeroWhenNothingWaitsAndGrowsWithTheOldestUnsentRow() throws Exception {
        assertThat(outbox.oldestPendingAgeSeconds()).isZero();

        assess("PAY-AGE-1", "10.00").andExpect(status().isCreated());
        jdbc.update("update " + OUTBOX + " set created_at = now() - interval '2 hours'");

        assertThat(outbox.oldestPendingAgeSeconds()).isBetween(7200.0, 7300.0);
        jdbc.update("update " + OUTBOX + " set parked_at = now()");
        assertThat(outbox.oldestPendingAgeSeconds()).as("parked rows are counted by outbox.parked.rows instead").isZero();
    }

    /** ADR-021 decision 4 against PostgreSQL: a non-payload failure stops the batch and writes nothing to the row. */
    @Test
    @SuppressWarnings("unchecked")
    void aNonPayloadFailureStopsTheBatchMarksNothingAndTheNextRunPublishesBothInOrder() throws Exception {
        assess("PAY-STOP-1", "10.00").andExpect(status().isCreated());
        assess("PAY-STOP-2", "20.00").andExpect(status().isCreated());
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(new KafkaProducerException(
            new ProducerRecord<>("evt.rsk.risk.assessed.v1", "k", "v"), "Failed to send",
            new org.apache.kafka.common.errors.NetworkException("broker down"))));
        OutboxRelay relay = relayOver(outbox);

        assertThat(relay.relayOnce()).isZero();

        assertThat(jdbc.queryForList("select attempts, last_error, first_failed_at, parked_at, published_at from " + OUTBOX))
            .hasSize(2)
            .allSatisfy(row -> {
                assertThat(row.get("attempts")).isEqualTo(0);
                assertThat(row.get("last_error")).isNull();
                assertThat(row.get("first_failed_at")).isNull();
                assertThat(row.get("parked_at")).isNull();
                assertThat(row.get("published_at")).isNull();
            });
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(2);
        Mockito.verify(kafka, Mockito.times(1)).send(any(ProducerRecord.class));

        Mockito.reset(kafka);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        Thread.sleep(1_100); // past the first backoff step (risk.outbox.relay.interval, 1 s)
        assertThat(relay.relayOnce()).isEqualTo(2);

        ArgumentCaptor<ProducerRecord<String, String>> records = ArgumentCaptor.forClass(ProducerRecord.class);
        Mockito.verify(kafka, Mockito.times(2)).send(records.capture());
        assertThat(records.getAllValues()).extracting(ProducerRecord::key)
            .as("created_seq order").containsExactlyElementsOf(
                jdbc.queryForList("select aggregate_id from " + OUTBOX + " order by created_seq", String.class));
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    /**
     * Two replicas relay at the same moment, deterministically: the winner's
     * first send blocks until the loser has called tryRelayLock, so the loser
     * always asks for the lock while the winner's transaction (and lock) is
     * open. With the advisory lock the loser gets false and sends nothing;
     * without it (query replaced by "select true") the loser reads the same
     * unpublished rows and every event is sent twice.
     */
    @Test
    @SuppressWarnings("unchecked")
    void twoRelaysRunningConcurrentlySendEachRowExactlyOnce() throws Exception {
        for (int i = 1; i <= 5; i++) {
            assess("PAY-CONC-" + i, "10.00").andExpect(status().isCreated());
        }
        Map<String, Integer> sends = new java.util.concurrent.ConcurrentHashMap<>();
        CountDownLatch winnerSending = new CountDownLatch(1);
        CountDownLatch loserAskedForTheLock = new CountDownLatch(1);
        Thread[] winnerThread = new Thread[1];
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            ProducerRecord<String, String> record = call.getArgument(0);
            sends.merge(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8), 1, Integer::sum);
            if (Thread.currentThread() == winnerThread[0] && winnerSending.getCount() > 0) {
                winnerSending.countDown();
                assertThat(loserAskedForTheLock.await(20, TimeUnit.SECONDS))
                    .as("the loser asked for the lock while the winner's transaction was open").isTrue();
            }
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
        OutboxRelay winner = relayOver(outbox);
        OutboxRelay loser = relayOver(afterTryRelayLock(outbox, loserAskedForTheLock::countDown));
        ExecutorService replicas = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = replicas.submit(() -> {
                winnerThread[0] = Thread.currentThread();
                return winner.relayOnce();
            });
            assertThat(winnerSending.await(20, TimeUnit.SECONDS)).as("the winner holds the lock and is sending").isTrue();
            Future<Integer> second = replicas.submit(loser::relayOnce);

            int loserPublished = second.get(30, TimeUnit.SECONDS);
            int winnerPublished = first.get(30, TimeUnit.SECONDS);

            assertThat(sends.values()).as("each event sent exactly once").containsOnly(1);
            assertThat(sends).hasSize(5);
            assertThat(loserPublished).as("the loser did not get the lock").isZero();
            assertThat(winnerPublished).isEqualTo(5);
            assertThat(outbox.countByPublishedAtIsNull()).isZero();
        } finally {
            replicas.shutdownNow();
        }
    }

    private OutboxRelay relayOver(SpringDataOutboxRepository repository) {
        return new OutboxRelay(repository, kafka, new TransactionTemplate(transactionManager),
            Clock.systemUTC(), 10, Duration.ofSeconds(30), Duration.ofDays(7),
            Duration.ofSeconds(1), Duration.ofMinutes(5), new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    /** The real repository, with a hook that runs after each tryRelayLock call returns. */
    private static SpringDataOutboxRepository afterTryRelayLock(SpringDataOutboxRepository real, Runnable hook) {
        return (SpringDataOutboxRepository) java.lang.reflect.Proxy.newProxyInstance(
            SpringDataOutboxRepository.class.getClassLoader(), new Class<?>[] {SpringDataOutboxRepository.class},
            (proxy, method, args) -> {
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                } finally {
                    if (method.getName().equals("tryRelayLock")) {
                        hook.run();
                    }
                }
            });
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
                {"transactionId": "%s", "amount": %s, "currency": "USD", "highRiskCountry": true, "velocityScore": 80, "paymentType": "TRANSFER"}
                """.formatted(transactionId, amount)));
    }
}
