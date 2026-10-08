package com.bank.risk;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * application.yml against the platform Kafka client guide: the base producer
 * settings, and the kafka-msk (Amazon MSK, IAM auth) and kafka-strimzi (mutual
 * TLS, PEM) profiles. Settings are bound exactly as Spring Boot binds them, and
 * the MSK producer is built, so a wrong mechanism or class name fails here
 * rather than at the first event in an AWS environment.
 */
class KafkaProfilesTest {

    @Test
    void baseProducerFollowsThePlatformGuide() {
        KafkaProperties kafka = bind(null);
        Map<String, Object> producer = kafka.buildProducerProperties(null);

        assertThat(producer).containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "svc-rsk-decisioning")
            .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
            .containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4")
            .containsEntry(ProducerConfig.LINGER_MS_CONFIG, "5")
            .containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
            .containsEntry("security.protocol", "PLAINTEXT");
        long deliveryTimeout = Long.parseLong(producer.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG).toString());
        long requestTimeout = Long.parseLong(producer.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG).toString());
        assertThat(deliveryTimeout).as("Kafka refuses delivery.timeout.ms < linger.ms + request.timeout.ms")
            .isGreaterThanOrEqualTo(5 + requestTimeout);
        assertThat(kafka.getAdmin().isAutoCreate()).as("topics come from the platform catalog only").isFalse();
    }

    @Test
    void producerBlocksAtMostTenSecondsSoOneRelayTickCannotHoldTheLockForLong() {
        Map<String, Object> producer = bind(null).buildProducerProperties(null);

        // Without it, a missing topic blocks send() for the 60 s default plus the send timeout,
        // while the relay holds its DB connection and the advisory lock.
        assertThat(producer).containsEntry(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
    }

    @Test
    void theMskProducerIsBuiltWithTheTenSecondBlockLimit() {
        Map<String, Object> producer = bind("kafka-msk").buildProducerProperties(null);
        producer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9098");

        assertThat(new ProducerConfig(producer).getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(10_000L);
    }

    @Test
    void kafkaMskProfileBuildsAnIamAuthenticatedProducer() {
        Map<String, Object> producer = bind("kafka-msk").buildProducerProperties(null);

        assertThat(producer).containsEntry("security.protocol", "SASL_SSL")
            .containsEntry("sasl.mechanism", "AWS_MSK_IAM")
            .containsEntry("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;")
            .containsEntry("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");

        producer.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9098");
        // Constructing the producer loads the login module and callback handler; no broker is contacted here.
        try (KafkaProducer<String, String> client = new KafkaProducer<>(producer)) {
            assertThat(client).isNotNull();
            client.close(Duration.ZERO);
        }
    }

    @Test
    void kafkaStrimziProfileUsesMutualTlsWithPemFromTheEnvironment() {
        PropertySource<?> strimzi = profileDocument("kafka-strimzi");

        assertThat(strimzi.getProperty("spring.kafka.security.protocol")).hasToString("SSL");
        assertThat(strimzi.getProperty("spring.kafka.ssl.key-store-type")).hasToString("PEM");
        assertThat(strimzi.getProperty("spring.kafka.ssl.trust-store-type")).hasToString("PEM");
        assertThat(strimzi.getProperty("spring.kafka.ssl.key-store-certificate-chain")).hasToString("${KAFKA_TLS_CERT}");
        assertThat(strimzi.getProperty("spring.kafka.ssl.key-store-key")).hasToString("${KAFKA_TLS_KEY}");
        assertThat(strimzi.getProperty("spring.kafka.ssl.trust-store-certificates")).hasToString("${KAFKA_TLS_CA}");
    }

    /** Platform alert rules select on app and squad, common tags on every meter, set by the chart's env. */
    @Test
    void everyMeterCarriesTheAppAndSquadCommonTagsFromTheEnvironment() {
        PropertySource<?> base = documents().getFirst();

        assertThat(base.getProperty("management.metrics.tags.app")).hasToString("${METRICS_APP:risk-decisioning-service}");
        assertThat(base.getProperty("management.metrics.tags.squad")).hasToString("${METRICS_SQUAD:risk}");
    }

    private static KafkaProperties bind(String profile) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        if (profile != null) {
            sources.addFirst(profileDocument(profile));
        }
        sources.addLast(documents().getFirst());
        return Binder.get(environment).bind("spring.kafka", KafkaProperties.class).get();
    }

    private static PropertySource<?> profileDocument(String profile) {
        return documents().stream()
            .filter(document -> profile.equals(String.valueOf(document.getProperty("spring.config.activate.on-profile"))))
            .findFirst()
            .orElseThrow(() -> new AssertionError("application.yml has no " + profile + " profile document"));
    }

    private static List<PropertySource<?>> documents() {
        try {
            return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

}
