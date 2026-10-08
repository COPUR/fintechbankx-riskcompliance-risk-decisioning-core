package com.bank.risk;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The "msk" profile (Amazon MSK, IAM client auth) must give a producer
 * configuration Kafka accepts: a wrong mechanism, JAAS line or handler class
 * fails here instead of at the first event in an AWS environment.
 */
class MskProfileTest {

    @Test
    void mskProfileBuildsAnIamAuthenticatedProducer() throws Exception {
        PropertySource<?> msk = mskDocument();
        assertThat(msk.getProperty("spring.kafka.security.protocol")).hasToString("SASL_SSL");

        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9098");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put("security.protocol", msk.getProperty("spring.kafka.security.protocol").toString());
        for (String key : List.of("sasl.mechanism", "sasl.jaas.config", "sasl.client.callback.handler.class")) {
            Object value = msk.getProperty("spring.kafka.properties." + key);
            assertThat(value).as(key).isNotNull();
            config.put(key, value.toString());
        }
        assertThat(config.get("sasl.mechanism")).isEqualTo("AWS_MSK_IAM");

        // Constructing the producer loads the login module and callback handler; no broker is contacted here.
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            assertThat(producer).isNotNull();
            producer.close(Duration.ZERO);
        }
    }

    private static PropertySource<?> mskDocument() throws Exception {
        for (PropertySource<?> document : new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"))) {
            if ("msk".equals(String.valueOf(document.getProperty("spring.config.activate.on-profile")))) {
                return document;
            }
        }
        throw new AssertionError("application.yml has no msk profile document");
    }
}
