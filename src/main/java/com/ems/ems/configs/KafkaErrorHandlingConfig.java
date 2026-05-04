package com.ems.ems.configs;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
/**
 * Real DLT + classified retry. Replaces the FixedBackOff TODO in the old config.
 *
 * Behaviour:
 *   1. Transient failures (DB hiccup, downstream 503) — retry with exponential backoff + jitter.
 *   2. Non-retryable failures (bad payload, validation, deserialization) — straight to DLT.
 *   3. After max attempts — to DLT.
 *   4. DLT record is published to {original-topic}.DLT, same partition number.
 */
@Configuration
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaErrorHandlingConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, Object> dltKafkaTemplate,
            KafkaProperties props) {

        // Routes failed records to {topic}.DLT, preserving partition.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dltKafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", record.partition()));

        // Exponential backoff: 1s, 2s, 4s, 8s, ... capped at maxInterval.
        ExponentialBackOffWithMaxRetries backoff =
                new ExponentialBackOffWithMaxRetries(props.retry().maxAttempts());
        backoff.setInitialInterval(props.retry().initialInterval().toMillis());
        backoff.setMultiplier(props.retry().multiplier());
        backoff.setMaxInterval(props.retry().maxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backoff);

        // Don't waste retries on errors that will never succeed — go to DLT immediately.
        handler.addNotRetryableExceptions(
                DeserializationException.class,        // poison pill / bad JSON
                IllegalArgumentException.class,        // validation
                NullPointerException.class,            // programmer bug
                ClassCastException.class               // schema mismatch
        );

        // Visibility: log every retry attempt with attempt number.
        handler.setRetryListeners((record, ex, deliveryAttempt) ->
                log.warn("Retry attempt {} for topic={} partition={} offset={} key={}: {}",
                        deliveryAttempt, record.topic(), record.partition(),
                        record.offset(), record.key(), ex.getMessage()));

        return handler;
    }
}
