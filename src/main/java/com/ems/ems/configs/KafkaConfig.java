package com.ems.ems.configs;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import com.ems.ems.events.DepartmentEvent;

/**
 * Producer/consumer factories. Topic provisioning lives in KafkaTopicsConfig,
 * error handling/DLT in KafkaErrorHandlingConfig. Single Responsibility — easier
 * to override per-profile and easier to review.
 */
@Configuration
@EnableKafka
@EnableConfigurationProperties(KafkaProperties.class)
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private final KafkaProperties props;

    public KafkaConfig(KafkaProperties props) {
        this.props = props;
    }

    // ---------- Producer ----------

    @Bean
    public ProducerFactory<String, DepartmentEvent> producerFactory() {
        Map<String, Object> p = new HashMap<>();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ProducerConfig.CLIENT_ID_CONFIG, props.producer().clientId());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Durability + ordering
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        p.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        p.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);

        // Throughput
        p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, props.producer().compressionType());
        p.put(ProducerConfig.LINGER_MS_CONFIG, props.producer().lingerMs());
        p.put(ProducerConfig.BATCH_SIZE_CONFIG, props.producer().batchSize());

        // Timeouts
        p.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, (int) props.producer().deliveryTimeout().toMillis());
        p.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) props.producer().requestTimeout().toMillis());

        // JsonSerializer: don't add type headers — keeps the wire format clean and
        // decouples consumer from producer's exact class name.
        p.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaProducerFactory<>(p);
    }

    @Bean
    public KafkaTemplate<String, DepartmentEvent> kafkaTemplate(
            ProducerFactory<String, DepartmentEvent> pf) {
        KafkaTemplate<String, DepartmentEvent> tpl = new KafkaTemplate<>(pf);
        tpl.setObservationEnabled(true);  // Micrometer + tracing propagation
        return tpl;
    }

    /**
     * Generic template for the DLT recoverer — sends Object so it can publish
     * any failed record (including DeserializationException-wrapped raw bytes).
     */
    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate(ProducerFactory<String, ?> pf) {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        ProducerFactory<String, Object> cast = (ProducerFactory) pf;
        return new KafkaTemplate<>(cast);
    }

    // ---------- Consumer ----------

    @Bean
    public ConsumerFactory<String, DepartmentEvent> consumerFactory() {
        Map<String, Object> p = new HashMap<>();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, props.consumer().clientId());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, props.consumer().groupId());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.consumer().autoOffsetReset());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, props.consumer().isolationLevel());

        // Polling/session — these prevent rebalance storms under load.
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, props.consumer().maxPollRecords());
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, (int) props.consumer().maxPollInterval().toMillis());
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, (int) props.consumer().sessionTimeout().toMillis());
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, (int) props.consumer().heartbeatInterval().toMillis());

        // Wrap deserializers so a poison pill doesn't kill the consumer.
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        p.put(ErrorHandlingDeserializer.KEY_DESERIALIZER_CLASS, StringDeserializer.class);
        p.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);

        p.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ems.ems.events");
        p.put(JsonDeserializer.VALUE_DEFAULT_TYPE, DepartmentEvent.class.getName());
        p.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(p);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, DepartmentEvent>
            kafkaListenerContainerFactory(
                    ConsumerFactory<String, DepartmentEvent> cf,
                    CommonErrorHandler errorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, DepartmentEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setConcurrency(props.consumer().concurrency());
        factory.setCommonErrorHandler(errorHandler);

        ContainerProperties cp = factory.getContainerProperties();
        cp.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        cp.setObservationEnabled(true);  // tracing + Micrometer

        return factory;
    }
}
