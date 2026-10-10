package com.bank.risk.infrastructure.config;

import java.util.Map;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Kafka transport at startup, the sibling of {@link DatabaseTlsGuard}. On AWS
 * the outbox relay publishes decisions to Amazon MSK with IAM authentication,
 * which exists only over SASL_SSL (profile kafka-msk). A values override
 * (KAFKA_SECURITY_PROTOCOL, a producer-level property, a wrong profile) could
 * downgrade the producer to PLAINTEXT or to SSL without SASL, and the relay
 * would then publish unauthenticated or in clear. Before any Kafka bean
 * exists, this guard builds the producer properties exactly as Spring Boot
 * does ({@link KafkaProperties#buildProducerProperties}: the producer's own
 * settings win over the common ones) and refuses to start unless the effective
 * {@code security.protocol} is {@value #REQUIRED_PROTOCOL}.
 *
 * <p>Registered by {@link KafkaTlsConfiguration} only when DB_SSL_ROOT_CERT is
 * set (the chart always sets it: an AWS environment with Aurora) and the relay
 * is on (risk.outbox.relay.enabled=true). Local runs and the Strimzi
 * mutual-TLS profile on a non-AWS cluster have no bundle, so the guard is off
 * for them.
 */
public final class KafkaTlsGuard implements BeanFactoryPostProcessor {

    static final String RELAY_PROPERTY = "risk.outbox.relay.enabled";
    static final String REQUIRED_PROTOCOL = "SASL_SSL";

    private static final String KAFKA_PREFIX = "spring.kafka";
    private static final String SECURITY_PROTOCOL = "security.protocol";

    private final Environment environment;

    KafkaTlsGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        verify();
    }

    void verify() {
        KafkaProperties kafka = Binder.get(environment)
                .bind(KAFKA_PREFIX, KafkaProperties.class)
                .orElseGet(KafkaProperties::new);
        Map<String, Object> producer = kafka.buildProducerProperties(null);
        Object protocol = producer.get(SECURITY_PROTOCOL);
        if (!REQUIRED_PROTOCOL.equals(protocol)) {
            throw new IllegalStateException(KAFKA_PREFIX + " producer " + SECURITY_PROTOCOL + " must be "
                    + REQUIRED_PROTOCOL + " when the outbox relay is on and " + DatabaseTlsGuard.ROOT_CERT_PROPERTY
                    + " is set (Amazon MSK with IAM authentication, profile kafka-msk), got "
                    + (protocol == null ? "none (Kafka defaults to PLAINTEXT)" : protocol));
        }
    }
}
