package com.ems.ems;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test that verifies the full Spring context starts.
 *
 * Renamed from TestEmsApplicationTests - Spring convention is the <Type>Tests
 * suffix, never a "Test" prefix.
 *
 * Uses the "it" profile + MySQL Testcontainer so the context actually wires
 * against a real database instead of failing on missing datasource.
 *
 * NOTE: once the old TestEmsApplicationTests.java is deleted, delete the
 * legacy file - the two classes in different packages would both run and
 * double the CI time.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("it")
class EmsApplicationTests {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ems")
            .withUsername("ems")
            .withPassword("ems");

    @Test
    void contextLoads() {
        // If the context fails to boot, this method never runs and the test fails
        // with the underlying Spring startup error.
    }
}
