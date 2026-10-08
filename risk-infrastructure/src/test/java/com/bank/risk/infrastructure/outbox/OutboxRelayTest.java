package com.bank.risk.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions = inlineTransactions();
    private static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
    private static final Duration BACKOFF_CAP = Duration.ofMinutes(5);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(NOW);
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, clock, 50,
        Duration.ofSeconds(1), Duration.ofDays(7), BACKOFF_BASE, BACKOFF_CAP, registry);

    @Test
    void theRelayLockKeyIsRiskSpecific() {
        assertThat(OutboxRelay.RELAY_LOCK_KEY).isEqualTo(0x72736B5F6F7574L).isNotEqualTo(0x6375735F6F7574L);
    }

    @Test
    void allRowsAreSentInOrderWhenKafkaAccepts() {
        OutboxEventJpaEntity first = row("RISK-1");
        OutboxEventJpaEntity second = row("RISK-2");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(2);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).isNull();
    }

    @Test
    void anInterruptedSendStopsTheBatchAndKeepsTheInterruptFlag() {
        OutboxEventJpaEntity first = row("RISK-1");
        CompletableFuture<SendResult<String, String>> pending = new CompletableFuture<>();
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, row("RISK-1")));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(pending);

        Thread.currentThread().interrupt();
        try {
            assertThat(relay.relayOnce()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(first.getLastError()).as("an interrupted send does not mark the row").isNull();
        assertThat(first.getAttempts()).isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @Test
    void aVeryLongErrorIsTruncatedToTheColumnSize() {
        OutboxEventJpaEntity row = row("RISK-3");

        row.markFailed("x".repeat(600));

        assertThat(row.getLastError()).hasSize(512);
        row.markFailed(null);
        assertThat(row.getLastError()).isNull();
        assertThat(row.getAttempts()).isEqualTo(2);
    }

    @Test
    void aTransactionTemplateReturningNullCountsAsNothingDone() {
        TransactionTemplate nothing = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return null;
            }
        };
        OutboxRelay idle = new OutboxRelay(outbox, kafka, nothing, Clock.fixed(NOW, ZoneOffset.UTC), 50,
            Duration.ofSeconds(1), Duration.ofDays(7), BACKOFF_BASE, BACKOFF_CAP, registry);

        assertThat(idle.relayOnce()).isZero();
        assertThat(idle.purgePublished()).isZero();
    }

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        assertThat(relay.relayOnce()).isZero();
        verify(outbox, never()).findUnpublishedBatch(50);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aRetryableFailureStopsTheBatchSoLaterEventsCannotOvertakeIt() {
        OutboxEventJpaEntity first = row("RISK-1");
        OutboxEventJpaEntity second = row("RISK-1");
        OutboxEventJpaEntity third = row("RISK-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getParkedAt()).as("a retryable failure is retried, not parked").isNull();
        assertThat(second.getAttempts()).as("ADR-021 decision 4: the row is not marked").isZero();
        assertThat(second.getLastError()).isNull();
        assertThat(third.getAttempts()).isZero();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void aSendThatTimesOutLocallyIsRetryable() {
        OutboxEventJpaEntity first = row("RISK-1");
        OutboxEventJpaEntity second = row("RISK-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        assertThat(relay.relayOnce()).isZero();

        assertThat(first.getParkedAt()).isNull();
        assertThat(first.getLastError()).isNull();
        assertThat(registry.get("outbox.send.failures").tag("exception", "TimeoutException").counter().count())
            .isEqualTo(1.0);
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    static Stream<RuntimeException> permanentFailures() {
        return Stream.of(
            new RecordTooLargeException("too large"),
            new SerializationException("cannot serialize"),
            new org.apache.kafka.common.errors.InvalidTopicException("bad topic"));
    }

    /**
     * Not payload-specific: a credential, IAM or ACL problem (or anything not
     * classified) affects every row alike, so parking it would park the whole
     * queue one row at a time. It stops the batch like a retriable failure.
     */
    static Stream<RuntimeException> notPayloadSpecificFailures() {
        return Stream.of(
            new org.apache.kafka.common.errors.SaslAuthenticationException("bad IAM signature"),
            new org.apache.kafka.common.errors.AuthenticationException("not authenticated"),
            new org.apache.kafka.common.errors.AuthorizationException("not authorized"),
            new TopicAuthorizationException(Set.of("evt.rsk.risk.assessed.v1")),
            new org.apache.kafka.common.KafkaException("unclassified Kafka failure"),
            new IllegalStateException("any other exception"));
    }

    @ParameterizedTest
    @MethodSource("notPayloadSpecificFailures")
    void anAuthOrUnclassifiedFailureStopsTheBatchAndParksNothing(RuntimeException failure) {
        OutboxEventJpaEntity first = row("RISK-1");
        OutboxEventJpaEntity next = row("RISK-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(failure)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isZero();

        assertThat(first.getParkedAt()).isNull();
        assertThat(first.getAttempts()).as("the row is not marked").isZero();
        assertThat(first.getLastError()).isNull();
        assertThat(first.getFirstFailedAt()).isNull();
        assertThat(next.getParkedAt()).isNull();
        assertThat(next.getPublishedAt()).as("the batch stopped at the failing row").isNull();
        assertThat(next.getAttempts()).isZero();
        verify(kafka, times(1)).send(any(ProducerRecord.class));
    }

    @ParameterizedTest
    @MethodSource("permanentFailures")
    void aPermanentFailureParksTheRowAndTheNextRowIsPublished(RuntimeException permanent) {
        OutboxEventJpaEntity poison = row("RISK-1");
        OutboxEventJpaEntity next = row("RISK-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(permanent)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).startsWith(permanent.getClass().getSimpleName());
        assertThat(next.getPublishedAt()).isEqualTo(NOW);
        assertThat(next.getParkedAt()).isNull();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    @Test
    void twentyRetryableFailuresInARowDoNotParkTheRow() {
        OutboxEventJpaEntity row = row("RISK-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(call -> CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        for (int run = 0; run < 20; run++) {
            assertThat(relay.relayOnce()).isZero();
            clock.advance(BACKOFF_CAP.plusSeconds(1));
        }

        assertThat(row.getParkedAt()).as("retryable failures never park").isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getFirstFailedAt()).isNull();
        verify(kafka, times(20)).send(any(ProducerRecord.class));
    }

    @Test
    void aPermanentFailureParksAtOnceAndRecordsTheReason() {
        OutboxEventJpaEntity poison = row("RISK-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))));

        relay.relayOnce();

        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getLastError()).startsWith("RecordTooLargeException");
        assertThat(poison.getParkedAt()).isEqualTo(NOW);
    }

    /** ADR-021 decision 4: a non-payload failure never parks or skips a row, however long it lasts. */
    @ParameterizedTest
    @MethodSource("nonPayloadFailuresLastingADay")
    void aNonPayloadFailureLastingMoreThan24HoursNeverParksAndTheNextRowIsNotSent(RuntimeException failure) {
        OutboxEventJpaEntity stuck = row("RISK-1");
        OutboxEventJpaEntity next = row("RISK-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(stuck, next));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(call -> CompletableFuture.failedFuture(producerFailure(failure)));

        for (Duration elapsed = Duration.ZERO; elapsed.compareTo(Duration.ofHours(25)) <= 0;
                elapsed = elapsed.plus(BACKOFF_CAP)) {
            assertThat(relay.relayOnce()).isZero();
            clock.advance(BACKOFF_CAP);
        }

        assertThat(stuck.getParkedAt()).isNull();
        assertThat(stuck.getAttempts()).isZero();
        assertThat(next.getParkedAt()).isNull();
        assertThat(next.getPublishedAt()).isNull();
        verify(kafka, times(301).description("only the head row is ever tried")).send(any(ProducerRecord.class));
    }

    static Stream<RuntimeException> nonPayloadFailuresLastingADay() {
        return Stream.of(new NetworkException("broker down"),
            new org.apache.kafka.common.errors.SaslAuthenticationException("bad IAM signature"),
            new TopicAuthorizationException(Set.of("evt.rsk.risk.assessed.v1")));
    }

    @Test
    void aNonPayloadFailureMarksNothingOnTheRow() {
        OutboxEventJpaEntity row = row("RISK-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        relay.relayOnce();

        assertThat(row.getAttempts()).isZero();
        assertThat(row.getFirstFailedAt()).isNull();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getParkedAt()).isNull();
        assertThat(row.getPublishedAt()).isNull();
    }

    @Test
    void theBackoffMustBePositiveAndItsCapNotBelowTheBase() {
        for (Duration[] invalid : List.of(new Duration[] {Duration.ZERO, BACKOFF_CAP},
                new Duration[] {Duration.ofMinutes(10), Duration.ofMinutes(5)})) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> new OutboxRelay(outbox, kafka, transactions,
                    clock, 50, Duration.ofSeconds(1), Duration.ofDays(7), invalid[0], invalid[1], registry))
                .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void afterAStoppedBatchTheRelayBacksOffExponentiallyUpToTheCap() {
        OutboxEventJpaEntity row = row("RISK-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(call -> CompletableFuture.failedFuture(producerFailure(new NetworkException("broker down"))));

        relay.relayOnce();
        int sends = 1;
        long[] expectedDelaysSeconds = {1, 2, 4, 8, 16, 32, 64, 128, 256, 300, 300};
        for (long delay : expectedDelaysSeconds) {
            clock.advance(Duration.ofSeconds(delay).minusMillis(1));
            relay.relayOnce();
            verify(kafka, times(sends).description("still backing off, " + delay + " s delay"))
                .send(any(ProducerRecord.class));
            clock.advance(Duration.ofMillis(1));
            relay.relayOnce();
            verify(kafka, times(++sends).description("retried after " + delay + " s")).send(any(ProducerRecord.class));
        }
        verify(outbox, times(sends).description("a backed-off run does not even take the lock"))
            .tryRelayLock(anyLong());
    }

    @Test
    void aSuccessfulRunResetsTheBackoff() {
        OutboxEventJpaEntity row = row("RISK-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("down"))))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("down"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("down"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        relay.relayOnce();                         // fails, waits 1 s
        clock.advance(Duration.ofSeconds(1));
        relay.relayOnce();                         // fails, waits 2 s
        clock.advance(Duration.ofSeconds(2));
        assertThat(relay.relayOnce()).isEqualTo(1); // succeeds, backoff reset
        clock.advance(Duration.ofMillis(1));
        relay.relayOnce();                         // fails again: waits 1 s, not 4 s
        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(1);
        verify(kafka, times(5)).send(any(ProducerRecord.class));
    }

    @Test
    void aParkedPayloadFailureDoesNotBackOff() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("RISK-1"))).thenReturn(List.of());
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))));

        relay.relayOnce();
        relay.relayOnce();

        verify(outbox, times(2)).tryRelayLock(anyLong());
    }

    @Test
    void everyFailedSendIsCountedByItsExceptionClass() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("RISK-1")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new NetworkException("down"))));

        relay.relayOnce();
        relay.relayOnce();

        assertThat(registry.get("outbox.send.failures").tag("exception", "RecordTooLargeException").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("outbox.send.failures").tag("exception", "NetworkException").counter().count())
            .isEqualTo(1.0);
        assertThat(registry.get("outbox.send.failures").counters())
            .allMatch(counter -> counter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey)
                .toList().equals(List.of("exception")), "one tag, exception; app and squad come as common tags");
    }

    @Test
    void aRelayParkCountsOneParkedEventTaggedWithItsExceptionAndMarksTheRowCounted() {
        OutboxEventJpaEntity poison = row("RISK-1");
        OutboxEventJpaEntity next = row("RISK-2");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, next)).thenReturn(List.of());
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        relay.relayOnce();
        relay.relayOnce();

        assertThat(registry.get("outbox.parked.events").tag("exception", "RecordTooLargeException").counter().count())
            .isEqualTo(1.0);
        assertThat(poison.isParkCounted()).as("written as counted in the same update as parked_at").isTrue();
        assertThat(next.isParkCounted()).isFalse();
        assertThat(registry.get("outbox.parked.events").counters())
            .allMatch(counter -> counter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey)
                .toList().equals(List.of("exception")), "one tag, exception");
    }

    @Test
    void anOperatorParkIsCountedExactlyOnceAsOperatorPark() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of());
        // The UPDATE marks the uncounted rows, so a second tick finds none of them.
        when(outbox.markOperatorParksCounted()).thenReturn(2).thenReturn(0);

        relay.relayOnce();
        relay.relayOnce();

        assertThat(registry.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count())
            .isEqualTo(2.0);
        verify(outbox, times(2)).markOperatorParksCounted();
    }

    @Test
    void operatorParksAreNotCountedByAReplicaWithoutTheLock() {
        when(outbox.tryRelayLock(anyLong())).thenReturn(false);

        relay.relayOnce();

        verify(outbox, never()).markOperatorParksCounted();
        assertThat(registry.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count()).isZero();
    }

    @Test
    void parkedCountsAreRecordedOnlyOnceTheTransactionCommits() {
        TransactionTemplate failingCommit = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                action.doInTransaction(new SimpleTransactionStatus());
                throw new org.springframework.transaction.TransactionSystemException("commit failed");
            }
        };
        OutboxRelay rolledBack = new OutboxRelay(outbox, kafka, failingCommit, clock, 50,
            Duration.ofSeconds(1), Duration.ofDays(7), BACKOFF_BASE, BACKOFF_CAP, registry);
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.markOperatorParksCounted()).thenReturn(1);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(row("RISK-1")));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(producerFailure(new RecordTooLargeException("too large"))));

        org.assertj.core.api.Assertions.assertThatThrownBy(rolledBack::relayOnce)
            .hasMessageContaining("commit failed");

        assertThat(registry.get("outbox.parked.events").counters())
            .as("rolled-back parks are counted by the run that commits them, never twice")
            .allMatch(counter -> counter.count() == 0.0);
    }

    @Test
    void parkedCountersStartAtZeroSoTheFirstParkIsAnIncrease() {
        for (String cause : List.of("OperatorPark", "RecordTooLargeException", "SerializationException",
                "InvalidTopicException")) {
            assertThat(registry.get("outbox.parked.events").tag("exception", cause).counter().count())
                .as(cause).isZero();
        }
    }

    /** A clock the test moves forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private OutboxRelay relayAt(Instant now) {
        return new OutboxRelay(outbox, kafka, transactions, Clock.fixed(now, ZoneOffset.UTC), 50,
            Duration.ofSeconds(1), Duration.ofDays(7), BACKOFF_BASE, BACKOFF_CAP, registry);
    }

    /** What KafkaTemplate completes its future with when the producer reports a failure. */
    private static KafkaProducerException producerFailure(Throwable cause) {
        return new KafkaProducerException(new ProducerRecord<>("evt.rsk.risk.assessed.v1", "k", "v"),
            "Failed to send", cause);
    }

    @Test
    void recordIsKeyedByAggregateAndCarriesTracingHeaders() {
        OutboxEventJpaEntity row = row("RISK-9");

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row);

        assertThat(record.topic()).isEqualTo("evt.rsk.risk.assessed.v1");
        assertThat(record.key()).isEqualTo("RISK-9");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(header(record, "eventType")).isEqualTo("Risk.RiskAssessment.Assessed.v1");
        assertThat(header(record, "eventId")).isEqualTo(row.getEventId().toString());
        assertThat(header(record, "correlationId")).isEqualTo("corr-9");
        assertThat(header(record, "x-fapi-interaction-id")).isEqualTo("corr-9");
    }

    @Test
    void theTraceContextOfTheOriginalRequestTravelsAsATraceparentHeader() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        OutboxEventJpaEntity traced = new OutboxEventJpaEntity(UUID.randomUUID(), "RiskAssessment", "RISK-10", 0L,
            "Risk.RiskAssessment.Assessed.v1", "evt.rsk.risk.assessed.v1", "{}", "corr-10", NOW, traceparent);

        assertThat(header(OutboxRelay.toRecord(traced), "traceparent")).isEqualTo(traceparent);
        assertThat(OutboxRelay.toRecord(row("RISK-11")).headers().lastHeader("traceparent"))
            .as("no trace, no invented header").isNull();
    }

    @Test
    void purgeDeletesRowsPublishedBeforeTheRetentionWindow() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        assertThat(relay.purgePublished()).isEqualTo(3);
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @Test
    void rowKeepsEveryColumnItWasWrittenWith() {
        UUID id = UUID.randomUUID();
        OutboxEventJpaEntity row = new OutboxEventJpaEntity(id, "RiskAssessment", "RISK-4", 0L,
            "Risk.RiskAssessment.Assessed.v1", "evt.rsk.risk.assessed.v1", "{}", "corr-4", NOW, null);

        assertThat(row.getEventId()).isEqualTo(id);
        assertThat(row.getAggregateType()).isEqualTo("RiskAssessment");
        assertThat(row.getAggregateVersion()).isZero();
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getParkedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "RiskAssessment", aggregateId, 0L,
            "Risk.RiskAssessment.Assessed.v1", "evt.rsk.risk.assessed.v1", "{}", "corr-9", NOW, null);
    }

    private static TransactionTemplate inlineTransactions() {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }
}
