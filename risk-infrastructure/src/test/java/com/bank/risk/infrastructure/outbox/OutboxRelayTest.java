package com.bank.risk.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(1), Duration.ofDays(7));

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
        assertThat(first.getLastError()).isEqualTo("interrupted");
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
            Duration.ofSeconds(1), Duration.ofDays(7));

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
    void aFailedSendStopsTheBatchSoLaterEventsCannotOvertakeIt() {
        OutboxEventJpaEntity first = row("RISK-1");
        OutboxEventJpaEntity second = row("RISK-1");
        OutboxEventJpaEntity third = row("RISK-1");
        when(outbox.tryRelayLock(anyLong())).thenReturn(true);
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isEqualTo(1);
        assertThat(second.getLastError()).isEqualTo("ExecutionException");
        assertThat(third.getAttempts()).isZero();
        verify(kafka, times(2)).send(any(ProducerRecord.class));
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
            "Risk.RiskAssessment.Assessed.v1", "evt.rsk.risk.assessed.v1", "{}", "corr-4", NOW);

        assertThat(row.getEventId()).isEqualTo(id);
        assertThat(row.getAggregateType()).isEqualTo("RiskAssessment");
        assertThat(row.getAggregateVersion()).isZero();
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "RiskAssessment", aggregateId, 0L,
            "Risk.RiskAssessment.Assessed.v1", "evt.rsk.risk.assessed.v1", "{}", "corr-9", NOW);
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
