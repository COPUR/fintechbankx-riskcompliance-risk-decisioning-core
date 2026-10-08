package com.bank.risk.infrastructure.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (Postgres advisory lock), so the service can
 * scale out without reordering events. Consumers de-duplicate on eventId,
 * which makes the at-least-once delivery safe.
 *
 * A failed send is handled by what failed (ADR-021 decision 4):
 * <ul>
 *   <li>payload errors (RecordTooLarge, Serialization, InvalidTopic): this row
 *   can never be sent, so it is parked (parked_at set, reason in last_error,
 *   counted once in outbox.parked.events) and the batch continues with the next row;</li>
 *   <li>everything else, including retriable, authorization and SASL/IAM
 *   failures and any unclassified exception: the batch stops without marking
 *   the row or anything after it, and the relay retries with exponential
 *   backoff. Such a row is never skipped or parked; the failure shows in
 *   outbox.send.failures and outbox.oldest.pending.age.seconds.</li>
 * </ul>
 * An operator can park a row stuck on a non-payload error by hand (runbook
 * "Parked outbox events"); parked rows are skipped until replayed. The next
 * run that holds the lock counts such a row once, tagged OperatorPark.
 *
 * outbox.parked.events (Prometheus outbox_parked_events_total, platform alert
 * OutboxEventsParked) counts each parked row exactly once: park_counted is
 * written in the same transaction, and the counter is incremented only after
 * that transaction commits.
 *
 * Each risk assessment raises exactly one event, so parking a row never puts
 * two events of one aggregate out of order.
 */
public class OutboxRelay {

    // Distinct per service (customer uses "cus_out"), so relays of services that
    // ever share a Postgres cluster never block each other.
    static final long RELAY_LOCK_KEY = 0x72736B5F6F7574L; // "rsk_out"
    static final String SEND_FAILURES = "outbox.send.failures";
    static final String PARKED_EVENTS = "outbox.parked.events";
    static final String OPERATOR_PARK = "OperatorPark";
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final MeterRegistry registry;
    /** Consecutive runs that stopped on a failure; 0 after a run that did not stop. */
    private int stoppedRuns;
    private Instant nextAttemptAt;

    /**
     * @param backoffBase first wait after a stopped batch (the poll interval); doubles per stopped run
     * @param backoffCap  longest wait between attempts
     * @param registry    receives outbox.send.failures{service, exception}
     */
    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention,
                       Duration backoffBase, Duration backoffCap, MeterRegistry registry) {
        if (backoffBase == null || backoffBase.isZero() || backoffBase.isNegative()
                || backoffCap == null || backoffCap.compareTo(backoffBase) < 0) {
            throw new IllegalArgumentException("relay backoff must be positive, with max-backoff not below the base");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
        this.registry = registry;
        // Start the known causes at zero, so the first park is an increase the alert sees.
        for (String cause : List.of(OPERATOR_PARK, RecordTooLargeException.class.getSimpleName(),
                SerializationException.class.getSimpleName(), InvalidTopicException.class.getSimpleName())) {
            parkedCounter(cause);
        }
    }

    /**
     * @return number of events published in this run
     */
    public synchronized int relayOnce() {
        if (nextAttemptAt != null && clock.instant().isBefore(nextAttemptAt)) {
            return 0; // backing off after a stopped batch; not even the lock is taken
        }
        boolean[] stopped = {false};
        int[] operatorParks = {0};
        List<String> relayParks = new ArrayList<>();
        Integer published = transactions.execute(status -> {
            operatorParks[0] = 0;
            relayParks.clear();
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            // Before the batch is loaded, so the update cannot race the rows parked below.
            operatorParks[0] = outbox.markOperatorParksCounted();
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    countFailure(e);
                    stopped[0] = true;
                    break;
                } catch (Exception e) {
                    countFailure(e);
                    if (!isPayloadSpecific(e)) {
                        // Not this record's fault: stop without marking the row; retried after backoff.
                        log.warn("Outbox relay could not publish event {} to {}: {}; batch stopped, will retry",
                            row.getEventId(), row.getTopic(), describe(e), e);
                        stopped[0] = true;
                        break;
                    }
                    Instant now = clock.instant();
                    row.markFailed(describe(e));
                    row.park(now);
                    relayParks.add(underlying(e).getClass().getSimpleName());
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; replay it by hand",
                        row.getEventId(), row.getTopic(), row.getAttempts(), row.getLastError(), e);
                }
            }
            return sent;
        });
        // Committed: count each park now (a rolled-back run left its rows uncounted and unparked).
        if (operatorParks[0] > 0) {
            parkedCounter(OPERATOR_PARK).increment(operatorParks[0]);
        }
        relayParks.forEach(cause -> parkedCounter(cause).increment());
        scheduleNextAttempt(stopped[0]);
        return published == null ? 0 : published;
    }

    /** Exponential backoff after a stopped batch (base, 2 x base, ... up to the cap); reset by any other run. */
    private void scheduleNextAttempt(boolean stopped) {
        if (!stopped) {
            stoppedRuns = 0;
            nextAttemptAt = null;
            return;
        }
        stoppedRuns++;
        Duration wait = backoffCap;
        if (stoppedRuns <= 30) {
            Duration doubled = backoffBase.multipliedBy(1L << (stoppedRuns - 1));
            if (doubled.compareTo(backoffCap) < 0) {
                wait = doubled;
            }
        }
        nextAttemptAt = clock.instant().plus(wait);
    }

    private void countFailure(Throwable failure) {
        // One tag only; app and squad are common tags (management.metrics.tags).
        Counter.builder(SEND_FAILURES)
            .description("Outbox rows Kafka did not accept, by exception class")
            .tag("exception", underlying(failure).getClass().getSimpleName())
            .register(registry)
            .increment();
    }

    private Counter parkedCounter(String cause) {
        // One tag only; app and squad are common tags (management.metrics.tags).
        return Counter.builder(PARKED_EVENTS)
            .description("Outbox rows parked, once per row, by exception class or OperatorPark")
            .tag("exception", cause)
            .register(registry);
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    /**
     * Payload-specific: the failure belongs to this record (too large, not
     * serializable, invalid topic name), so retrying can never succeed and the
     * next row may still go through. Every other failure, auth errors and
     * unclassified ones included, is treated as retryable.
     */
    static boolean isPayloadSpecific(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                return true;
            }
        }
        return false;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers. */
    static Throwable underlying(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    /** The underlying failure as text, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = underlying(failure);
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
