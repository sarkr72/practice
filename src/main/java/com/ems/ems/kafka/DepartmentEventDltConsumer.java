package com.ems.ems.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import com.ems.ems.configs.KafkaTopicsConfig;

/**
 * Consumes the DLT for observability. In a real bank this would:
 *   - emit a metric (counter) so PagerDuty fires on threshold
 *   - persist to a DB-backed "dead letter" table for human triage
 *   - expose a /admin/dlt endpoint to inspect and replay
 *
 * For now: structured ERROR log so it shows up in Kibana/Datadog/Splunk.
 *
 * Note: separate consumer factory (with byte[] deserializers) would be ideal here,
 * since DLT records can include malformed payloads. For brevity this uses the
 * default factory and logs DepartmentEvent — swap to byte[] when poison-pill DLT
 * cases need handling.
 */
@Component
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class DepartmentEventDltConsumer {

    private static final Logger log = LoggerFactory.getLogger(DepartmentEventDltConsumer.class);

    @KafkaListener(
            topics = KafkaTopicsConfig.DEPARTMENT_EVENTS_DLT,
            groupId = "${ems.kafka.consumer.group-id}-dlt",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onDeadLetter(ConsumerRecord<String, ?> record,
                             @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String exMessage,
                             @Header(name = KafkaHeaders.DLT_EXCEPTION_FQCN, required = false) String exClass,
                             @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) String originalTopic,
                             @Header(name = KafkaHeaders.DLT_ORIGINAL_PARTITION, required = false) Integer originalPartition,
                             @Header(name = KafkaHeaders.DLT_ORIGINAL_OFFSET, required = false) Long originalOffset) {

        log.error("DLT message received: originalTopic={} partition={} offset={} key={} exception={} message={}",
                originalTopic, originalPartition, originalOffset, record.key(), exClass, exMessage);

        // TODO: increment failure counter (Micrometer)
        //       e.g. meterRegistry.counter("kafka.dlt.received", "topic", originalTopic).increment();
    }
}
