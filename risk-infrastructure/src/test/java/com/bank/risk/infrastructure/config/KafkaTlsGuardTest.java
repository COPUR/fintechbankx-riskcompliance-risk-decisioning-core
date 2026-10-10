package com.bank.risk.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Kafka half of the startup TLS contract. In an AWS environment (the
 * chart always mounts the RDS CA bundle, so DB_SSL_ROOT_CERT is set) the
 * outbox relay must talk to MSK over SASL_SSL with IAM; any other producer
 * security.protocol (PLAINTEXT, or SSL without SASL) means a values override
 * or a wrong profile, and the service refuses to start rather than publish
 * decisions over an unauthenticated or unencrypted connection.
 *
 * <p>The guard is off when the relay is off (nothing publishes) and off
 * without DB_SSL_ROOT_CERT (local runs, the Strimzi mutual-TLS profile on a
 * non-AWS cluster).
 */
class KafkaTlsGuardTest {

    private static final String BUNDLE = "/etc/fintechbankx/rds-ca/global-bundle.pem";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaTlsConfiguration.class);

    @Test
    void refusesAPlaintextProducer() {
        assertThatThrownBy(() -> guard("PLAINTEXT").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.kafka producer security.protocol must be SASL_SSL")
                .hasMessageContaining("PLAINTEXT");
    }

    @Test
    void refusesSslWithoutSaslBecauseMskIamNeedsSaslSsl() {
        assertThatThrownBy(() -> guard("SSL").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.kafka producer security.protocol must be SASL_SSL");
    }

    @Test
    void acceptsSaslSsl() {
        assertThatCode(() -> guard("SASL_SSL").verify()).doesNotThrowAnyException();
    }

    /** The producer's own protocol wins over the common one, exactly as Spring Boot builds the producer. */
    @Test
    void readsTheEffectiveProducerProtocolNotOnlyTheCommonOne() {
        MockEnvironment downgraded = environment("SASL_SSL")
                .withProperty("spring.kafka.producer.security.protocol", "PLAINTEXT");
        MockEnvironment upgraded = environment("PLAINTEXT")
                .withProperty("spring.kafka.producer.security.protocol", "SASL_SSL");

        assertThatThrownBy(() -> new KafkaTlsGuard(downgraded).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.kafka producer security.protocol must be SASL_SSL");
        assertThatCode(() -> new KafkaTlsGuard(upgraded).verify()).doesNotThrowAnyException();
    }

    @Test
    void refusesAProtocolSetThroughTheRawProducerProperties() {
        MockEnvironment environment = environment("SASL_SSL")
                .withProperty("spring.kafka.producer.properties.security.protocol", "PLAINTEXT");

        assertThatThrownBy(() -> new KafkaTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.kafka producer security.protocol must be SASL_SSL");
    }

    @Test
    void refusesAMissingProtocolWhichWouldDefaultToPlaintext() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_SSL_ROOT_CERT", BUNDLE)
                .withProperty("risk.outbox.relay.enabled", "true");

        assertThatThrownBy(() -> new KafkaTlsGuard(environment).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.kafka producer security.protocol must be SASL_SSL");
    }

    @Test
    void refusesToStartTheContextWithAPlaintextProducerWhenTheRelayIsOnAndTheBundleIsMounted() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "risk.outbox.relay.enabled=true",
                        "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasMessageContaining("spring.kafka producer security.protocol must be SASL_SSL"));
    }

    @Test
    void startsTheContextWithSaslSslWhenTheRelayIsOnAndTheBundleIsMounted() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "risk.outbox.relay.enabled=true",
                        "spring.kafka.security.protocol=SASL_SSL")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(KafkaTlsGuard.class));
    }

    @Test
    void staysInactiveWhileTheRelayIsOffBecauseNothingPublishes() {
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
        contextRunner
                .withPropertyValues("DB_SSL_ROOT_CERT=" + BUNDLE, "risk.outbox.relay.enabled=false",
                        "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
    }

    /** Local runs and the Strimzi mutual-TLS profile (security.protocol SSL) have no RDS bundle and keep working. */
    @Test
    void staysInactiveWithoutTheBundleSoTheStrimziProfileStillStarts() {
        contextRunner
                .withPropertyValues("risk.outbox.relay.enabled=true", "spring.kafka.security.protocol=SSL")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
        contextRunner
                .withPropertyValues("risk.outbox.relay.enabled=true", "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(KafkaTlsGuard.class));
    }

    private static KafkaTlsGuard guard(String protocol) {
        return new KafkaTlsGuard(environment(protocol));
    }

    private static MockEnvironment environment(String protocol) {
        return new MockEnvironment()
                .withProperty("DB_SSL_ROOT_CERT", BUNDLE)
                .withProperty("risk.outbox.relay.enabled", "true")
                .withProperty("spring.kafka.security.protocol", protocol);
    }
}
