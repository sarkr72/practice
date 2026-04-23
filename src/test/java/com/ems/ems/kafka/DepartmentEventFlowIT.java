package com.ems.ems.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.stereotype.Component;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ems.ems.events.DepartmentEvent;
import com.ems.ems.events.EventType;

/**
 * End-to-end Kafka flow test.
 *
 * Runs a full Spring context with:
 *   - MariaDB : Testcontainers @ServiceConnection (wire-compatible with MySQL,
 *               boots in ~5s vs MySQL 8's ~2min on Docker Desktop)
 *   - Redis   : Testcontainers (GenericContainer on port 6379)
 *   - Kafka   : @EmbeddedKafka in-process broker
 *
 * Verifies:
 *   1. Producer -> topic round-trip: a published DepartmentEvent is consumed.
 *   2. Two distinct events are both consumed.
 *
 * Uses an auxiliary listener on a DIFFERENT consumer group so we can observe
 * messages independently of the real DepartmentEventConsumer (which owns the
 * Redis idempotency path). Kafka consumer groups get independent offsets, so
 * both listeners see every message.
 */
@SpringBootTest
@Testcontainers
@EmbeddedKafka(
        partitions = 3,
        topics = { "ems.department.events", "ems.department.events.DLT" }
)
@ActiveProfiles("kafka-it")
@DisplayName("DepartmentEvent producer -> consumer flow")
class DepartmentEventFlowIT {

    @Container
    @ServiceConnection
    static final MariaDBContainer<?> db = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("ems")
            .withUsername("ems")
            .withPassword("ems")
            .withStartupTimeout(Duration.ofMinutes(3))
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("db-tc")));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        // Embedded Kafka broker - Spring exposes its brokers list via a system property.
        registry.add("spring.kafka.bootstrap-servers",
                () -> System.getProperty("spring.embedded.kafka.brokers"));
        registry.add("ems.kafka.enabled", () -> "true");
        registry.add("spring.kafka.consumer.group-id", () -> "ems-it-" + UUID.randomUUID());

        // Strip MSK IAM auth so we can talk PLAINTEXT to the embedded broker.
        registry.add("spring.kafka.properties.security.protocol", () -> "PLAINTEXT");
        registry.add("spring.kafka.properties.sasl.mechanism", () -> "");

        // Redis testcontainer
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.ssl.enabled", () -> "false");
    }

    @Autowired
    private DepartmentEventProducer producer;

    @Autowired
    private ObservedEventCounter counter;

    @BeforeEach
    void reset() {
        counter.reset();
    }

    @Test
    @DisplayName("publish once -> consumer receives event")
    void publishOnce_consumerReceivesOne() {
        UUID eventId = UUID.randomUUID();
        producer.publish(new DepartmentEvent(
                eventId, EventType.CREATED, Instant.now(),
                100L, "Platform", "infra", 0L));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(counter.get(eventId)).isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("two distinct events are both consumed")
    void twoDistinctEvents_bothConsumed() {
        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();

        producer.publish(new DepartmentEvent(e1, EventType.CREATED, Instant.now(), 200L, "A", "a", 0L));
        producer.publish(new DepartmentEvent(e2, EventType.CREATED, Instant.now(), 201L, "B", "b", 0L));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(counter.get(e1)).isGreaterThanOrEqualTo(1);
            assertThat(counter.get(e2)).isGreaterThanOrEqualTo(1);
        });
    }

    /**
     * Auxiliary @KafkaListener on a different group-id so we can count deliveries
     * without interfering with the real DepartmentEventConsumer.
     *
     * Picked up by component scan because this package (com.ems.ems.kafka) sits
     * under the app's scan root.
     */
    @Component
    static class ObservedEventCounter {

        private final ConcurrentMap<UUID, AtomicInteger> counts = new ConcurrentHashMap<>();

        @KafkaListener(
                topics = "ems.department.events",
                groupId = "ems-it-observer",
                containerFactory = "kafkaListenerContainerFactory")
        void observe(DepartmentEvent event, Acknowledgment ack) {
            counts.computeIfAbsent(event.eventId(), k -> new AtomicInteger()).incrementAndGet();
            ack.acknowledge();
        }

        int get(UUID eventId) {
            AtomicInteger c = counts.get(eventId);
            return c == null ? 0 : c.get();
        }

        void reset() {
            counts.clear();
        }
    }
}