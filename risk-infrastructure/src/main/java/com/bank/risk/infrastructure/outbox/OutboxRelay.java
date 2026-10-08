package com.bank.risk.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering events. Consumers de-duplicate on eventId,
 * which makes the at-least-once delivery safe.
 *
 * A failed send is handled by what failed:
 * <ul>
 *   <li>retryable (a Kafka {@link RetriableException}, including the producer's
 *   own timeouts, or the relay's send timeout): the batch stops and the row is
 *   retried on the next run, so later events cannot overtake it;</li>
 *   <li>permanent (RecordTooLarge, Serialization, InvalidTopic,
 *   TopicAuthorization, anything else that is not retriable), or a retryable
 *   failure on the row's {@code maxAttempts}-th attempt: the row is parked
 *   (parked_at set, reason in last_error) and the batch continues with the next
 *   row. Parked rows are skipped until replayed by hand (runbook "Parked outbox
 *   events") and counted by the outbox.parked.events gauge.</li>
 * </ul>
 * Each risk assessment raises exactly one event, so parking a row never puts
 * two events of one aggregate out of order.
 */
public class OutboxRelay {

    // Distinct per service (customer uses "cus_out"), so relays of services that
    // ever share a Postgres cluster never block each other.
    static final long RELAY_LOCK_KEY = 0x72736B5F6F7574L; // "rsk_out"
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final int maxAttempts;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("risk.outbox.relay.max-attempts must be at least 1");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    row.markFailed("interrupted");
                    break;
                } catch (Exception e) {
                    row.markFailed(describe(e));
                    if (isRetryable(e) && row.getAttempts() < maxAttempts) {
                        log.warn("Outbox relay could not publish event {} to {} (attempt {} of {}); will retry",
                            row.getEventId(), row.getTopic(), row.getAttempts(), maxAttempts, e);
                        break;
                    }
                    row.park(clock.instant());
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; replay it by hand",
                        row.getEventId(), row.getTopic(), row.getAttempts(), row.getLastError(), e);
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    /** Retryable: a Kafka RetriableException (timeouts included) or the relay's own send timeout. */
    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.getTopic(), row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            // W3C trace context, so the consumer's span joins the producer's trace.
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
