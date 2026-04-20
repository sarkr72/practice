package com.ems.ems.controllers;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.ems.ems.dtos.DepartmentDto;
import com.ems.ems.services.DepartmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.ServletException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DepartmentController")
class DepartmentControllerTest {

    private static final String BASE_URL = "/api/departments";
    private static final Long DEPT_ID = 1L;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private DepartmentService departmentService;

    @InjectMocks
    private DepartmentController departmentController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(departmentController).build();
    }

    // ───────────────────── test data builders ─────────────────────

    private DepartmentDto buildDepartmentDto(Long id, String name, String description, Integer count) {
        DepartmentDto dto = new DepartmentDto();
        dto.setId(id);
        dto.setName(name);
        dto.setDescription(description);
        dto.setEmployeeCount(count);
        return dto;
    }

    private DepartmentDto buildDefaultDepartment() {
        return buildDepartmentDto(DEPT_ID, "Engineering", "Software engineering department", 42);
    }

    // ───────────────────── POST /api/departments ─────────────────────

    @Nested
    @DisplayName("POST /api/departments")
    class CreateDepartment {

        @Test
        @DisplayName("should return 201 and created department when request is valid")
        void createDepartment_validRequest_returns201() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "Engineering", "Software engineering department", null);
            DepartmentDto response = buildDefaultDepartment();

            given(departmentService.createDepartment(any(DepartmentDto.class))).willReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Department created successfully"))
                    .andExpect(jsonPath("$.data.id").value(DEPT_ID))
                    .andExpect(jsonPath("$.data.name").value("Engineering"))
                    .andExpect(jsonPath("$.data.description").value("Software engineering department"))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(departmentService).createDepartment(any(DepartmentDto.class));
        }

        @Test
        @DisplayName("should return 400 when department name is blank")
        void createDepartment_blankName_returns400() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "", "Some description", null);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(departmentService, never()).createDepartment(any(DepartmentDto.class));
        }

        @Test
        @DisplayName("should return 400 when department name is null")
        void createDepartment_nullName_returns400() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, null, "Some description", null);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(departmentService, never()).createDepartment(any(DepartmentDto.class));
        }

        @Test
        @DisplayName("should return 400 when request body is missing")
        void createDepartment_noBody_returns400() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 415 when content type is not JSON")
        void createDepartment_wrongContentType_returns415() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("not json"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }

    // ───────────────────── GET /api/departments/{id} ─────────────────────

    @Nested
    @DisplayName("GET /api/departments/{id}")
    class GetDepartmentById {

        @Test
        @DisplayName("should return 200 and department when found")
        void getDepartment_existingId_returns200() throws Exception {
            DepartmentDto department = buildDefaultDepartment();

            given(departmentService.getDepartmentById(DEPT_ID)).willReturn(department);

            mockMvc.perform(get(BASE_URL + "/{id}", DEPT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(DEPT_ID))
                    .andExpect(jsonPath("$.data.name").value("Engineering"))
                    .andExpect(jsonPath("$.data.employeeCount").value(42));

            verify(departmentService).getDepartmentById(DEPT_ID);
        }

        @Test
        @DisplayName("should throw when department not found")
        void getDepartment_nonExistingId_throwsException() {
            given(departmentService.getDepartmentById(999L))
                    .willThrow(new RuntimeException("Department not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(get(BASE_URL + "/{id}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Department not found with id: 999");
        }

        @Test
        @DisplayName("should return 400 when id is not a number")
        void getDepartment_invalidId_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", "abc"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ───────────────────── GET /api/departments ─────────────────────

    @Nested
    @DisplayName("GET /api/departments")
    class GetAllDepartments {

        @Test
        @DisplayName("should return 200 and list of departments")
        void getAllDepartments_departmentsExist_returnsList() throws Exception {
            DepartmentDto dept1 = buildDepartmentDto(1L, "Engineering", "Eng dept", 42);
            DepartmentDto dept2 = buildDepartmentDto(2L, "Finance", "Finance dept", 15);

            given(departmentService.getAllDepartments()).willReturn(List.of(dept1, dept2));

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].name").value("Engineering"))
                    .andExpect(jsonPath("$.data[1].name").value("Finance"));
        }

        @Test
        @DisplayName("should return 200 and empty list when no departments exist")
        void getAllDepartments_noDepartments_returnsEmptyList() throws Exception {
            given(departmentService.getAllDepartments()).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ───────────────────── PUT /api/departments/{id} ─────────────────────

    @Nested
    @DisplayName("PUT /api/departments/{id}")
    class UpdateDepartment {

        @Test
        @DisplayName("should return 200 and updated department when request is valid")
        void updateDepartment_validRequest_returns200() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "Engineering v2", "Updated description", null);
            DepartmentDto response = buildDepartmentDto(DEPT_ID, "Engineering v2", "Updated description", 42);

            given(departmentService.updateDepartment(eq(DEPT_ID), any(DepartmentDto.class))).willReturn(response);

            mockMvc.perform(put(BASE_URL + "/{id}", DEPT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Department updated successfully"))
                    .andExpect(jsonPath("$.data.name").value("Engineering v2"));

            verify(departmentService).updateDepartment(eq(DEPT_ID), any(DepartmentDto.class));
        }

        @Test
        @DisplayName("should return 400 when department name is blank on update")
        void updateDepartment_blankName_returns400() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "", "description", null);

            mockMvc.perform(put(BASE_URL + "/{id}", DEPT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(departmentService, never()).updateDepartment(any(), any());
        }

        @Test
        @DisplayName("should throw when updating non-existing department")
        void updateDepartment_nonExistingId_throwsException() {
            DepartmentDto request = buildDepartmentDto(null, "Engineering", "desc", null);

            given(departmentService.updateDepartment(eq(999L), any(DepartmentDto.class)))
                    .willThrow(new RuntimeException("Department not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(put(BASE_URL + "/{id}", 999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Department not found with id: 999");
        }
    }

    // ───────────────────── DELETE /api/departments/{id} ─────────────────────

    @Nested
    @DisplayName("DELETE /api/departments/{id}")
    class DeleteDepartment {

        @Test
        @DisplayName("should return 200 when department deleted successfully")
        void deleteDepartment_existingId_returns200() throws Exception {
            willDoNothing().given(departmentService).deleteDepartment(DEPT_ID);

            mockMvc.perform(delete(BASE_URL + "/{id}", DEPT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Department deleted successfully"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(departmentService).deleteDepartment(DEPT_ID);
        }

        @Test
        @DisplayName("should throw when deleting non-existing department")
        void deleteDepartment_nonExistingId_throwsException() {
            willThrow(new RuntimeException("Department not found with id: 999"))
                    .given(departmentService).deleteDepartment(999L);

            assertThatThrownBy(() ->
                    mockMvc.perform(delete(BASE_URL + "/{id}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Department not found with id: 999");
        }
    }
}
