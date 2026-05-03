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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/*
 * Integration test for Spring context.
 *
 * IMPORTANT:
 * - Testcontainers is managed by Spring Boot automatically via @ServiceConnection
 * - DO NOT manually start containers here
 * - This avoids Docker detection issues in CI and WSL
 */

@SpringBootTest
@Testcontainers
@ActiveProfiles("it")
class EmsApplicationTests {

    @Container
    @ServiceConnection
    static MariaDBContainer<?> db =
            new MariaDBContainer<>("mariadb:11.4")
                    .withDatabaseName("ems")
                    .withUsername("ems")
                    .withPassword("ems");


    @Test
    void contextLoads() {}
}