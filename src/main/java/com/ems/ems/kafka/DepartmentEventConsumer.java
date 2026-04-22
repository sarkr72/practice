package com.ems.ems.kafka;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import com.ems.ems.events.DepartmentEvent;

@Component
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class DepartmentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DepartmentEventConsumer.class);
    private static final String IDEMPOTENCY_KEY_PREFIX = "ems:event:processed:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;

    public DepartmentEventConsumer(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @KafkaListener(
            topics = "ems.department.events",
            groupId = "${spring.kafka.consumer.group-id:ems-department-consumer}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(@Payload DepartmentEvent event,
                        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                        @Header(KafkaHeaders.OFFSET) long offset,
                        Acknowledgment ack) {

        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + event.eventId();

        Boolean firstTime = redisTemplate.opsForValue()
                .setIfAbsent(idempotencyKey, "1", IDEMPOTENCY_TTL);

        if (Boolean.FALSE.equals(firstTime)) {
            log.info("Skipping duplicate eventId={} partition={} offset={}",
                    event.eventId(), partition, offset);
            ack.acknowledge();
            return;
        }

        try {
            process(event);
            ack.acknowledge();
        } catch (Exception ex) {
            // Roll back the idempotency marker so a retry can actually reprocess.
            redisTemplate.delete(idempotencyKey);
            log.error("Failed to process eventId={} — will retry", event.eventId(), ex);
            throw ex; // DefaultErrorHandler takes it from here
        }
    }

    private void process(DepartmentEvent event) {
        // Placeholder. Real consumers would: update a read model, push to a search index,
        // emit an outbound webhook, record to an audit log, etc.
        log.info("Processing eventId={} type={} departmentId={} name={}",
                event.eventId(), event.eventType(), event.departmentId(), event.name());
    }
}
