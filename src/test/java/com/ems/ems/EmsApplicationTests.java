package com.ems.ems;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@ActiveProfiles("it")
class EmsApplicationTests {

    @Container
    @ServiceConnection
    static final MariaDBContainer<?> db = new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("ems")
            .withUsername("ems")
            .withPassword("ems")
            .withStartupTimeout(Duration.ofMinutes(3))
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("db-tc")));

    @Test
    void contextLoads() {
    }
}