package com.ems.ems.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ems.ems.dtos.DepartmentDto;
import com.ems.ems.repositories.DepartmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration test - boots the full Spring context against a real MySQL container.
 * Matches the *IT.java Failsafe pattern used in Jenkinsfile's "Integration Tests" stage.
 *
 * Kafka is disabled via application-it.yml so this doesn't need a broker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("it")
@DisplayName("DepartmentIT - full-stack against MySQL Testcontainer")
class DepartmentIT {

    @Container
    @ServiceConnection
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ems")
            .withUsername("ems")
            .withPassword("ems");

    @DynamicPropertySource
    static void overrides(DynamicPropertyRegistry registry) {
        // Belt-and-braces in case @ServiceConnection isn't wired for some auto-config.
        registry.add("ems.kafka.enabled", () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DepartmentRepository departmentRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("full CRUD flow: create -> get -> update -> delete")
    void crudFlow() throws Exception {
        // CREATE
        DepartmentDto create = DepartmentDto.builder()
                .name("Platform Engineering")
                .description("infra + devex")
                .build();

        String body = mockMvc.perform(post("/api/v1/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(create)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.name").value("Platform Engineering"))
                .andReturn().getResponse().getContentAsString();

        Long id = objectMapper.readTree(body).path("data").path("id").asLong();
        assertThat(id).isPositive();

        // GET
        mockMvc.perform(get("/api/v1/departments/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Platform Engineering"));

        // UPDATE
        DepartmentDto update = DepartmentDto.builder()
                .name("Platform Engineering")
                .description("updated desc")
                .build();
        mockMvc.perform(put("/api/v1/departments/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.description").value("updated desc"));

        // DELETE
        mockMvc.perform(delete("/api/v1/departments/{id}", id))
                .andExpect(status().isOk());

        assertThat(departmentRepository.existsById(id)).isFalse();
    }

    @Test
    @DisplayName("duplicate name returns 409")
    void duplicateNameReturns409() throws Exception {
        DepartmentDto dto = DepartmentDto.builder()
                .name("Risk")
                .description("first")
                .build();

        mockMvc.perform(post("/api/v1/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/departments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("get non-existent returns 404 with standard error shape")
    void getMissingReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/departments/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("list endpoint returns all departments")
    void listReturnsAll() throws Exception {
        DepartmentDto a = DepartmentDto.builder().name("A-Team").description("a").build();
        DepartmentDto b = DepartmentDto.builder().name("B-Team").description("b").build();

        mockMvc.perform(post("/api/v1/departments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(a))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/departments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(b))).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }
}
