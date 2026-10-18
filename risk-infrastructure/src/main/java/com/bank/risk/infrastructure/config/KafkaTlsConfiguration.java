package com.bank.risk.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link KafkaTlsGuard} by default, under the same switch as
 * {@link DatabaseTlsConfiguration} ({@link StartupAssertions}: only the local
 * profile or the test switch leave it out). The producer must then be
 * configured for TLS: SASL_SSL to MSK with IAM (profile kafka-msk) or SSL
 * with the Strimzi KafkaUser certificate (profile kafka-strimzi). The outbox
 * relay flag decides whether anything publishes, not whether the producer
 * configuration is checked, so a PLAINTEXT producer is refused on the first
 * rollout rather than on the one that switches the relay on. Not imported by
 * the migrate-only {@code DatabaseMigration} context, which has no Kafka.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTlsConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any Kafka producer or relay bean exists. */
    @Bean
    @Conditional(StartupAssertions.On.class)
    static KafkaTlsGuard kafkaTlsGuard(Environment environment) {
        return new KafkaTlsGuard(environment);
    }
}
