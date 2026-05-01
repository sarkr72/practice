package com.ems.ems.kafka;

import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.ems.ems.configs.KafkaTopicsConfig;
import com.ems.ems.events.DepartmentEvent;

@Component
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class DepartmentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DepartmentEventProducer.class);

    // Header names — visible at the wire level, useful for ops without deserializing payload.
    public static final String HEADER_EVENT_ID   = "x-event-id";
    public static final String HEADER_EVENT_TYPE = "x-event-type";

    private final KafkaTemplate<String, DepartmentEvent> kafkaTemplate;

    public DepartmentEventProducer(KafkaTemplate<String, DepartmentEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(DepartmentEvent event) {
        String key = String.valueOf(event.departmentId());

        // Use ProducerRecord directly so we can attach headers. eventId in a header
        // means consumer idempotency checks (and DLT triage) don't need to parse JSON.
        ProducerRecord<String, DepartmentEvent> record =
                new ProducerRecord<>(KafkaTopicsConfig.DEPARTMENT_EVENTS_TOPIC, key, event);
        record.headers().add(HEADER_EVENT_ID,   event.eventId().toString().getBytes());
        record.headers().add(HEADER_EVENT_TYPE, event.eventType().name().getBytes());

        CompletableFuture<SendResult<String, DepartmentEvent>> future = kafkaTemplate.send(record);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish DepartmentEvent eventId={} type={} departmentId={}",
                        event.eventId(), event.eventType(), event.departmentId(), ex);
            } else {
                log.info("Published DepartmentEvent eventId={} type={} departmentId={} partition={} offset={}",
                        event.eventId(),
                        event.eventType(),
                        event.departmentId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}