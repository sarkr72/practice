package com.ems.ems.controllers;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.ems.ems.exceptions.DuplicateResourceException;
import com.ems.ems.exceptions.GlobalExceptionHandler;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.services.DepartmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
@DisplayName("DepartmentController")
class DepartmentControllerTest {

    private static final String BASE_URL = "/api/v1/departments";
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
        // Wire the GlobalExceptionHandler so error-path tests see real HTTP codes
        // instead of raw ServletException wrappers.
        mockMvc = MockMvcBuilders.standaloneSetup(departmentController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private DepartmentDto buildDepartmentDto(Long id, String name, String description, Integer count) {
        return DepartmentDto.builder()
                .id(id).name(name).description(description).employeeCount(count)
                .build();
    }

    private DepartmentDto buildDefaultDepartment() {
        return buildDepartmentDto(DEPT_ID, "Engineering", "Software engineering department", 42);
    }

    // ───────────────────── POST ─────────────────────

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateDepartment {

        @Test
        @DisplayName("returns 201 when valid")
        void valid_returns201() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "Engineering", "desc", null);
            given(departmentService.createDepartment(any(DepartmentDto.class)))
                    .willReturn(buildDefaultDepartment());

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Department created successfully"))
                    .andExpect(jsonPath("$.data.id").value(DEPT_ID))
                    .andExpect(jsonPath("$.data.name").value("Engineering"));

            verify(departmentService).createDepartment(any(DepartmentDto.class));
        }

        @Test
        @DisplayName("returns 400 when name is blank")
        void blankName_returns400() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "", "desc", null);
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
            verify(departmentService, never()).createDepartment(any());
        }

        @Test
        @DisplayName("returns 409 when service throws DuplicateResourceException")
        void duplicate_returns409() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "Engineering", "desc", null);
            given(departmentService.createDepartment(any(DepartmentDto.class)))
                    .willThrow(new DuplicateResourceException("Department", "name", "Engineering"));

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("returns 400 on malformed JSON")
        void malformedJson_returns400() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{not-json"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 415 on wrong content type")
        void wrongContentType_returns415() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("not json"))
                    .andExpect(status().isUnsupportedMediaType());
        }
    }

    // ───────────────────── GET /{id} ─────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetById {

        @Test
        @DisplayName("returns 200 when found")
        void found_returns200() throws Exception {
            given(departmentService.getDepartmentById(DEPT_ID)).willReturn(buildDefaultDepartment());

            mockMvc.perform(get(BASE_URL + "/{id}", DEPT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(DEPT_ID))
                    .andExpect(jsonPath("$.data.employeeCount").value(42));
        }

        @Test
        @DisplayName("returns 404 when not found")
        void notFound_returns404() throws Exception {
            given(departmentService.getDepartmentById(999L))
                    .willThrow(new ResourceNotFoundException("Department", "id", 999L));

            mockMvc.perform(get(BASE_URL + "/{id}", 999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false));
        }

        @Test
        @DisplayName("returns 400 when id is non-numeric")
        void invalidId_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", "abc"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ───────────────────── GET list ─────────────────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAll {

        @Test
        void listsDepartments() throws Exception {
            given(departmentService.getAllDepartments()).willReturn(List.of(
                    buildDepartmentDto(1L, "Engineering", "e", 42),
                    buildDepartmentDto(2L, "Finance", "f", 15)));

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(2)));
        }

        @Test
        void emptyListOk() throws Exception {
            given(departmentService.getAllDepartments()).willReturn(Collections.emptyList());
            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ───────────────────── PUT ─────────────────────

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class Update {

        @Test
        void valid_returns200() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "Eng v2", "desc", null);
            DepartmentDto response = buildDepartmentDto(DEPT_ID, "Eng v2", "desc", 42);
            given(departmentService.updateDepartment(eq(DEPT_ID), any(DepartmentDto.class)))
                    .willReturn(response);

            mockMvc.perform(put(BASE_URL + "/{id}", DEPT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value("Eng v2"));
        }

        @Test
        void notFound_returns404() throws Exception {
            DepartmentDto request = buildDepartmentDto(null, "X", "Y", null);
            given(departmentService.updateDepartment(eq(999L), any(DepartmentDto.class)))
                    .willThrow(new ResourceNotFoundException("Department", "id", 999L));

            mockMvc.perform(put(BASE_URL + "/{id}", 999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ───────────────────── DELETE ─────────────────────

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class Delete {

        @Test
        void ok_returns200() throws Exception {
            willDoNothing().given(departmentService).deleteDepartment(DEPT_ID);
            mockMvc.perform(delete(BASE_URL + "/{id}", DEPT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Department deleted successfully"));
        }

        @Test
        void notFound_returns404() throws Exception {
            willThrow(new ResourceNotFoundException("Department", "id", 999L))
                    .given(departmentService).deleteDepartment(999L);

            mockMvc.perform(delete(BASE_URL + "/{id}", 999L))
                    .andExpect(status().isNotFound());
        }
    }
}
