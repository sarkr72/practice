//package com.ems.ems;
//
//import java.time.Duration;
//
//import org.junit.jupiter.api.Test;
//import org.slf4j.LoggerFactory;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
//import org.springframework.test.context.ActiveProfiles;
//import org.testcontainers.containers.MariaDBContainer;
//import org.testcontainers.containers.output.Slf4jLogConsumer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//@SpringBootTest
//@Testcontainers
//@ActiveProfiles("it")
//class EmsApplicationTests {
//
//    @Container
//    @ServiceConnection
//    static final MariaDBContainer<?> db = new MariaDBContainer<>("mariadb:11.4")
//            .withDatabaseName("ems")
//            .withUsername("ems")
//            .withPassword("ems")
//            .withStartupTimeout(Duration.ofMinutes(3))
//            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("db-tc")));
//
//    @Test
//    void contextLoads() {
//    }
//}

package com.ems.ems;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.session.SessionAutoConfiguration",
    "ems.kafka.enabled=false",
    "spring.session.store-type=none",
    "spring.batch.job.enabled=false",
    "management.endpoint.health.group.readiness.include=readinessState,db"
})
class EmsApplicationTests {

    @Test
    void contextLoads() {}
}