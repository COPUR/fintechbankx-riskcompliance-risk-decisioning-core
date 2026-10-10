package com.bank.risk.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link KafkaTlsGuard} when the outbox relay is on and the chart
 * has mounted the RDS CA bundle (DB_SSL_ROOT_CERT is always set there, so the
 * service runs on AWS and must publish to MSK over SASL_SSL). With the relay
 * off nothing publishes; without the bundle (local runs, tests, the Strimzi
 * mutual-TLS profile on a non-AWS cluster) the guard stays off, as
 * {@link DatabaseTlsConfiguration}'s does.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = KafkaTlsGuard.RELAY_PROPERTY, havingValue = "true")
public class KafkaTlsConfiguration {

    /** Static: a BeanFactoryPostProcessor runs before any Kafka producer or relay bean exists. */
    @Bean
    @ConditionalOnProperty(DatabaseTlsGuard.ROOT_CERT_PROPERTY)
    static KafkaTlsGuard kafkaTlsGuard(Environment environment) {
        return new KafkaTlsGuard(environment);
    }
}
