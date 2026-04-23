package com.ems.ems.kafka;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import com.ems.ems.configs.KafkaConfig;
import com.ems.ems.events.DepartmentEvent;


@Component
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class DepartmentEventProducer {

    private static final Logger log = LoggerFactory.getLogger(DepartmentEventProducer.class);

    private final KafkaTemplate<String, DepartmentEvent> kafkaTemplate;

    public DepartmentEventProducer(KafkaTemplate<String, DepartmentEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(DepartmentEvent event) {
        String key = String.valueOf(event.departmentId());

        CompletableFuture<SendResult<String, DepartmentEvent>> future =
                kafkaTemplate.send(KafkaConfig.DEPARTMENT_EVENTS_TOPIC, key, event);

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