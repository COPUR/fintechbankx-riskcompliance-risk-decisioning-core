package com.bank.risk.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link KafkaTlsGuard} when the chart has mounted the RDS CA
 * bundle (DB_SSL_ROOT_CERT is always set there, so the service is deployed
 * and its producer must be configured for TLS: SASL_SSL to MSK with IAM,
 * profile kafka-msk, or SSL with the Strimzi KafkaUser certificate, profile
 * kafka-strimzi). The same condition as {@link DatabaseTlsConfiguration}'s,
 * and nothing else: the outbox relay flag decides whether anything publishes,
 * not whether the producer configuration is checked, so a PLAINTEXT producer
 * is refused on the first rollout rather than on the one that switches the
 * relay on. Without the bundle (local runs, tests) the guard stays off.
 */
@Configuration(proxyBeanMethods = false)
public class KafkaTlsConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any Kafka producer or relay bean exists. */
    @Bean
    @ConditionalOnProperty(DatabaseTlsGuard.ROOT_CERT_PROPERTY)
    static KafkaTlsGuard kafkaTlsGuard(Environment environment) {
        return new KafkaTlsGuard(environment);
    }
}
