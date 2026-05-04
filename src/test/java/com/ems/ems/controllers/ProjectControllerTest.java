package com.ems.ems.controllers;

import static org.hamcrest.Matchers.hasItem;
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

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.ems.ems.dtos.ProjectDto;
import com.ems.ems.exceptions.GlobalExceptionHandler;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.services.ProjectService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectController")
class ProjectControllerTest {

    private static final String BASE_URL = "/api/v1/projects";
    private static final Long PROJECT_ID = 1L;
    private static final Long EMP_ID = 10L;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private ProjectController projectController;

    @BeforeEach
    void setUp() {
        // Register the configured ObjectMapper so LocalDate serializes as ISO string.
        // Register the GlobalExceptionHandler so tests see real HTTP status codes,
        // not raw ServletException wrappers.
        MappingJackson2HttpMessageConverter converter =
                new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(projectController)
                .setMessageConverters(converter)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ─────────── test data builders ───────────

    private ProjectDto buildProjectDto(Long id, String name, String status, Set<Long> employeeIds) {
        return ProjectDto.builder()
                .id(id)
                .name(name)
                .description("Project description")
                .startDate(LocalDate.of(2024, 1, 15))
                .endDate(LocalDate.of(2024, 12, 31))
                .status(status)
                .employeeIds(employeeIds != null ? employeeIds : Set.of())
                .employeeCount(employeeIds != null ? employeeIds.size() : 0)
                .build();
    }

    private ProjectDto buildDefaultProject() {
        return buildProjectDto(PROJECT_ID, "Atlas", "IN_PROGRESS", Set.of(10L, 20L));
    }

    private ProjectDto buildCreateRequest() {
        return ProjectDto.builder()
                .name("Atlas")
                .description("Core banking migration")
                .startDate(LocalDate.of(2024, 1, 15))
                .endDate(LocalDate.of(2024, 12, 31))
                .status("PLANNED")
                .build();
    }

    // ─────────── POST /api/v1/projects ───────────

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateProject {

        @Test
        @DisplayName("returns 201 when valid")
        void valid_returns201() throws Exception {
            ProjectDto request = buildCreateRequest();
            given(projectService.createProject(any(ProjectDto.class))).willReturn(buildDefaultProject());

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Project created successfully"))
                    .andExpect(jsonPath("$.data.id").value(PROJECT_ID))
                    .andExpect(jsonPath("$.data.name").value("Atlas"))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.employeeIds", hasSize(2)));

            verify(projectService).createProject(any(ProjectDto.class));
        }

        @Test
        @DisplayName("returns 400 when name is blank")
        void blankName_returns400() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setName("");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));

            verify(projectService, never()).createProject(any(ProjectDto.class));
        }

        @Test
        @DisplayName("returns 400 when name is null")
        void nullName_returns400() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setName(null);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(projectService, never()).createProject(any(ProjectDto.class));
        }

        @Test
        @DisplayName("returns 400 when request body is missing")
        void noBody_returns400() throws Exception {
            mockMvc.perform(post(BASE_URL).contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("returns 415 when content type is not JSON")
        void wrongContentType_returns415() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("not json"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("accepts request when optional fields are null")
        void optionalFieldsNull_returns201() throws Exception {
            ProjectDto request = ProjectDto.builder().name("Minimal").build();
            ProjectDto response = buildProjectDto(2L, "Minimal", null, Set.of());

            given(projectService.createProject(any(ProjectDto.class))).willReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.name").value("Minimal"));
        }
    }

    // ─────────── GET /api/v1/projects/{id} ───────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetProjectById {

        @Test
        @DisplayName("returns 200 when found")
        void existingId_returns200() throws Exception {
            given(projectService.getProjectById(PROJECT_ID)).willReturn(buildDefaultProject());

            mockMvc.perform(get(BASE_URL + "/{id}", PROJECT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(PROJECT_ID))
                    .andExpect(jsonPath("$.data.name").value("Atlas"))
                    .andExpect(jsonPath("$.data.startDate").value("2024-01-15"))
                    .andExpect(jsonPath("$.data.endDate").value("2024-12-31"));

            verify(projectService).getProjectById(PROJECT_ID);
        }

        @Test
        @DisplayName("returns 404 when not found")
        void nonExistingId_returns404() throws Exception {
            given(projectService.getProjectById(999L))
                    .willThrow(new ResourceNotFoundException("Project", "id", 999L));

            mockMvc.perform(get(BASE_URL + "/{id}", 999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value("Project not found with id: '999'"));
        }

        @Test
        @DisplayName("returns 400 when id is not a number")
        void invalidId_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", "abc"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─────────── GET /api/v1/projects ───────────

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllProjects {

        @Test
        @DisplayName("returns list of projects")
        void projectsExist_returnsList() throws Exception {
            ProjectDto p1 = buildProjectDto(1L, "Atlas", "IN_PROGRESS", Set.of(10L));
            ProjectDto p2 = buildProjectDto(2L, "Mercury", "PLANNED", Set.of());

            given(projectService.getAllProjects()).willReturn(List.of(p1, p2));

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].name").value("Atlas"))
                    .andExpect(jsonPath("$.data[1].name").value("Mercury"));
        }

        @Test
        @DisplayName("returns empty list when no projects exist")
        void noProjects_returnsEmpty() throws Exception {
            given(projectService.getAllProjects()).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ─────────── PUT /api/v1/projects/{id} ───────────

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateProject {

        @Test
        @DisplayName("returns 200 when valid")
        void valid_returns200() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setStatus("COMPLETED");
            ProjectDto response = buildProjectDto(PROJECT_ID, "Atlas", "COMPLETED", Set.of(10L, 20L));

            given(projectService.updateProject(eq(PROJECT_ID), any(ProjectDto.class))).willReturn(response);

            mockMvc.perform(put(BASE_URL + "/{id}", PROJECT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"));

            verify(projectService).updateProject(eq(PROJECT_ID), any(ProjectDto.class));
        }

        @Test
        @DisplayName("returns 400 when name is blank on update")
        void blankName_returns400() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setName("");

            mockMvc.perform(put(BASE_URL + "/{id}", PROJECT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(projectService, never()).updateProject(any(), any());
        }

        @Test
        @DisplayName("returns 404 when updating non-existing project")
        void nonExistingId_returns404() throws Exception {
            ProjectDto request = buildCreateRequest();

            given(projectService.updateProject(eq(999L), any(ProjectDto.class)))
                    .willThrow(new ResourceNotFoundException("Project", "id", 999L));

            mockMvc.perform(put(BASE_URL + "/{id}", 999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }
    }

    // ─────────── DELETE /api/v1/projects/{id} ───────────

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteProject {

        @Test
        @DisplayName("returns 200 when deleted")
        void existingId_returns200() throws Exception {
            willDoNothing().given(projectService).deleteProject(PROJECT_ID);

            mockMvc.perform(delete(BASE_URL + "/{id}", PROJECT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Project deleted successfully"));

            verify(projectService).deleteProject(PROJECT_ID);
        }

        @Test
        @DisplayName("returns 404 when deleting non-existing")
        void nonExistingId_returns404() throws Exception {
            willThrow(new ResourceNotFoundException("Project", "id", 999L))
                    .given(projectService).deleteProject(999L);

            mockMvc.perform(delete(BASE_URL + "/{id}", 999L))
                    .andExpect(status().isNotFound());
        }
    }

    // ─────────── POST /api/v1/projects/{projectId}/employees/{employeeId} ───────────

    @Nested
    @DisplayName("POST " + BASE_URL + "/{projectId}/employees/{employeeId}")
    class AddEmployeeToProject {

        @Test
        @DisplayName("returns 200 when employee added")
        void validIds_returns200() throws Exception {
            ProjectDto response = buildProjectDto(PROJECT_ID, "Atlas", "IN_PROGRESS", Set.of(EMP_ID));
            given(projectService.addEmployeeToProject(PROJECT_ID, EMP_ID)).willReturn(response);

            mockMvc.perform(post(BASE_URL + "/{p}/employees/{e}", PROJECT_ID, EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.employeeIds", hasItem(EMP_ID.intValue())));

            verify(projectService).addEmployeeToProject(PROJECT_ID, EMP_ID);
        }

        @Test
        @DisplayName("returns 404 when project not found")
        void nonExistingProject_returns404() throws Exception {
            given(projectService.addEmployeeToProject(999L, EMP_ID))
                    .willThrow(new ResourceNotFoundException("Project", "id", 999L));

            mockMvc.perform(post(BASE_URL + "/{p}/employees/{e}", 999L, EMP_ID))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("returns 404 when employee not found")
        void nonExistingEmployee_returns404() throws Exception {
            given(projectService.addEmployeeToProject(PROJECT_ID, 999L))
                    .willThrow(new ResourceNotFoundException("Employee", "id", 999L));

            mockMvc.perform(post(BASE_URL + "/{p}/employees/{e}", PROJECT_ID, 999L))
                    .andExpect(status().isNotFound());
        }
    }

    // ─────────── DELETE /api/v1/projects/{projectId}/employees/{employeeId} ───────────

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{projectId}/employees/{employeeId}")
    class RemoveEmployeeFromProject {

        @Test
        @DisplayName("returns 200 when employee removed")
        void validIds_returns200() throws Exception {
            ProjectDto response = buildProjectDto(PROJECT_ID, "Atlas", "IN_PROGRESS", Set.of());
            given(projectService.removeEmployeeFromProject(PROJECT_ID, EMP_ID)).willReturn(response);

            mockMvc.perform(delete(BASE_URL + "/{p}/employees/{e}", PROJECT_ID, EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.employeeIds", hasSize(0)));

            verify(projectService).removeEmployeeFromProject(PROJECT_ID, EMP_ID);
        }

        @Test
        @DisplayName("returns 404 when project not found")
        void nonExistingProject_returns404() throws Exception {
            given(projectService.removeEmployeeFromProject(999L, EMP_ID))
                    .willThrow(new ResourceNotFoundException("Project", "id", 999L));

            mockMvc.perform(delete(BASE_URL + "/{p}/employees/{e}", 999L, EMP_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ─────────── GET /api/v1/projects/employee/{employeeId} ───────────

    @Nested
    @DisplayName("GET " + BASE_URL + "/employee/{employeeId}")
    class GetProjectsByEmployee {

        @Test
        @DisplayName("returns projects for given employee")
        void existingEmployee_returnsList() throws Exception {
            ProjectDto p1 = buildProjectDto(1L, "Atlas", "IN_PROGRESS", Set.of(EMP_ID));
            ProjectDto p2 = buildProjectDto(2L, "Mercury", "PLANNED", Set.of(EMP_ID));

            given(projectService.getProjectsByEmployee(EMP_ID)).willReturn(List.of(p1, p2));

            mockMvc.perform(get(BASE_URL + "/employee/{employeeId}", EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].name").value("Atlas"));

            verify(projectService).getProjectsByEmployee(EMP_ID);
        }

        @Test
        @DisplayName("returns empty list when employee has no projects")
        void noProjects_returnsEmpty() throws Exception {
            given(projectService.getProjectsByEmployee(EMP_ID)).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL + "/employee/{employeeId}", EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("returns 404 when employee not found")
        void nonExistingEmployee_returns404() throws Exception {
            given(projectService.getProjectsByEmployee(999L))
                    .willThrow(new ResourceNotFoundException("Employee", "id", 999L));

            mockMvc.perform(get(BASE_URL + "/employee/{employeeId}", 999L))
                    .andExpect(status().isNotFound());
        }
    }
}
