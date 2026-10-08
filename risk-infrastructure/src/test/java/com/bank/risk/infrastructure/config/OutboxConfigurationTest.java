package com.bank.risk.infrastructure.config;

import com.bank.risk.infrastructure.outbox.OutboxRelay;
import com.bank.risk.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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
        when(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).thenReturn(4L);

        configuration.riskOutboxPendingGauge(registry, outbox);

        Gauge gauge = registry.get("outbox.pending.events").tag("service", "svc-rsk-decisioning").gauge();
        assertThat(gauge.value()).as("parked rows are not waiting for the relay").isEqualTo(4.0);
    }

    @Test
    void oldestPendingAgeGaugeIsExportedInSecondsForThisService() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(outbox.oldestPendingAgeSeconds()).thenReturn(90.5);

        configuration.riskOutboxOldestPendingAgeGauge(registry, outbox);

        Gauge gauge = registry.get("outbox.oldest.pending.age.seconds").tag("service", "svc-rsk-decisioning").gauge();
        assertThat(gauge.value()).isEqualTo(90.5);
    }

    @Test
    void parkedGaugeIsExportedAsOutboxParkedEventsForThisService() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(outbox.countByPublishedAtIsNullAndParkedAtIsNotNull()).thenReturn(2L);

        configuration.riskOutboxParkedGauge(registry, outbox);

        Gauge gauge = registry.get("outbox.parked.events").tag("service", "svc-rsk-decisioning").gauge();
        assertThat(gauge.value()).isEqualTo(2.0);
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
            mock(PlatformTransactionManager.class), Clock.systemUTC(), 100, Duration.ofSeconds(10), Duration.ofDays(7),
            Duration.ofSeconds(1), Duration.ofMinutes(5), new SimpleMeterRegistry());

        assertThat(relay).isNotNull();
        assertThat(relayConfiguration.relaySchedule(relay)).isNotNull();
    }

    /** The relay publishes only when switched on (chart step 4 or OUTBOX_RELAY_ENABLED=true for local runs). */
    @Test
    void theRelayIsAbsentUnlessItIsSwitchedOn() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                .setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(OutboxConfiguration.RelayConfiguration.class)
            .withBean(SpringDataOutboxRepository.class, () -> outbox)
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(io.micrometer.core.instrument.MeterRegistry.class, SimpleMeterRegistry::new);

        runner.run(context -> assertThat(context).doesNotHaveBean(OutboxRelay.class));
        runner.withPropertyValues("risk.outbox.relay.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(OutboxRelay.class));
        runner.withPropertyValues("risk.outbox.relay.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(OutboxRelay.class));
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
