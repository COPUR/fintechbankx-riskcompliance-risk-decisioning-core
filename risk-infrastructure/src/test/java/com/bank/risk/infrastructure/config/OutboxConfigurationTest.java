package com.bank.risk.infrastructure.config;

import com.bank.risk.infrastructure.outbox.OutboxRelay;
import com.bank.risk.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxConfigurationTest {

    private final OutboxConfiguration configuration = new OutboxConfiguration();
    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);

    @Test
    void pendingGaugeIsExportedAsOutboxPendingEventsForThisService() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(outbox.countByPublishedAtIsNull()).thenReturn(4L);

        configuration.riskOutboxPendingGauge(registry, outbox);

        Gauge gauge = registry.get("outbox.pending.events").tag("service", "svc-rsk-decisioning").gauge();
        assertThat(gauge.value()).isEqualTo(4.0);
    }

    @Test
    void envelopeFactoryAndClockAreProvided() {
        assertThat(configuration.riskEventEnvelopeFactory(new ObjectMapper())).isNotNull();
        assertThat(configuration.clock().getZone()).isEqualTo(Clock.systemUTC().getZone());
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayAndScheduleAreWiredFromSettings() {
        OutboxConfiguration.RelayConfiguration relayConfiguration = new OutboxConfiguration.RelayConfiguration();

        OutboxRelay relay = relayConfiguration.outboxRelay(outbox, mock(KafkaTemplate.class),
            mock(PlatformTransactionManager.class), Clock.systemUTC(), 100, Duration.ofSeconds(10), Duration.ofDays(7));

        assertThat(relay).isNotNull();
        assertThat(relayConfiguration.relaySchedule(relay)).isNotNull();
    }

    @Test
    void scheduleRunsTheRelayAndThePurge() {
        OutboxRelay relay = mock(OutboxRelay.class);
        OutboxConfiguration.RelaySchedule schedule = new OutboxConfiguration.RelaySchedule(relay);

        schedule.relay();
        schedule.purge();

        verify(relay).relayOnce();
        verify(relay).purgePublished();
    }
}
