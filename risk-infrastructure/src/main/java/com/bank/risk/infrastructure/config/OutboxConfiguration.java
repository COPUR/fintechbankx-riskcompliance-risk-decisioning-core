package com.bank.risk.infrastructure.config;

import com.bank.risk.infrastructure.outbox.OutboxRelay;
import com.bank.risk.infrastructure.outbox.RiskEventEnvelopeFactory;
import com.bank.risk.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

@Configuration
public class OutboxConfiguration {

    static final String PENDING_GAUGE = "outbox.pending.events";
    static final String PARKED_GAUGE = "outbox.parked.events";

    @Bean
    RiskEventEnvelopeFactory riskEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new RiskEventEnvelopeFactory(objectMapper);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Backlog of events not yet on Kafka (Prometheus outbox_pending_events).
     * Alert on growth: the relay or the brokers are down while decisions keep
     * being made.
     */
    @Bean
    Gauge riskOutboxPendingGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder(PENDING_GAUGE, outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNull)
            .description("Risk events written to the outbox and waiting for the relay (parked rows excluded)")
            .tag("service", RiskEventEnvelopeFactory.PRODUCER)
            .register(registry);
    }

    /**
     * Events the relay gave up on (Prometheus outbox_parked_events). Alert on
     * any value above zero: a consumer is missing a decision until the row is
     * replayed (runbook "Parked outbox events").
     */
    @Bean
    Gauge riskOutboxParkedGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder(PARKED_GAUGE, outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNotNull)
            .description("Risk events the outbox relay parked after a permanent failure or too many attempts")
            .tag("service", RiskEventEnvelopeFactory.PRODUCER)
            .register(registry);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Disable with risk.outbox.relay.enabled=false
     * (tests, the first cutover step, or a dedicated relay deployment).
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "risk.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${risk.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${risk.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${risk.outbox.retention:P7D}") Duration retention,
                                @Value("${risk.outbox.relay.max-attempts:10}") int maxAttempts) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                sendTimeout, retention, maxAttempts);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${risk.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${risk.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
