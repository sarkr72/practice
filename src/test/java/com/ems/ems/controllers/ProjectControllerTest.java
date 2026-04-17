package com.ems.ems.controllers;

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
import com.ems.ems.services.ProjectService;
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
@DisplayName("ProjectController")
class ProjectControllerTest {

    private static final String BASE_URL = "/api/projects";
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
        // Register the configured ObjectMapper with MockMvc so response serialization
        // also writes dates as ISO strings (e.g. "2024-01-15") instead of arrays.
        MappingJackson2HttpMessageConverter converter = new MappingJackson2HttpMessageConverter(objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(projectController)
                .setMessageConverters(converter)
                .build();
    }

    // ───────────────────── test data builders ─────────────────────

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

    // ───────────────────── POST /api/projects ─────────────────────

    @Nested
    @DisplayName("POST /api/projects")
    class CreateProject {

        @Test
        @DisplayName("should return 201 and created project when request is valid")
        void createProject_validRequest_returns201() throws Exception {
            ProjectDto request = buildCreateRequest();
            ProjectDto response = buildDefaultProject();

            given(projectService.createProject(any(ProjectDto.class))).willReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Project created successfully"))
                    .andExpect(jsonPath("$.data.id").value(PROJECT_ID))
                    .andExpect(jsonPath("$.data.name").value("Atlas"))
                    .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.employeeIds", hasSize(2)))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(projectService).createProject(any(ProjectDto.class));
        }

        @Test
        @DisplayName("should return 400 when project name is blank")
        void createProject_blankName_returns400() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setName("");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(projectService, never()).createProject(any(ProjectDto.class));
        }

        @Test
        @DisplayName("should return 400 when project name is null")
        void createProject_nullName_returns400() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setName(null);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(projectService, never()).createProject(any(ProjectDto.class));
        }

        @Test
        @DisplayName("should return 400 when request body is missing")
        void createProject_noBody_returns400() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 415 when content type is not JSON")
        void createProject_wrongContentType_returns415() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("not json"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("should accept request when optional fields are null")
        void createProject_optionalFieldsNull_returns201() throws Exception {
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

    // ───────────────────── GET /api/projects/{id} ─────────────────────

    @Nested
    @DisplayName("GET /api/projects/{id}")
    class GetProjectById {

        @Test
        @DisplayName("should return 200 and project when found")
        void getProject_existingId_returns200() throws Exception {
            ProjectDto project = buildDefaultProject();

            given(projectService.getProjectById(PROJECT_ID)).willReturn(project);

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
        @DisplayName("should throw when project not found")
        void getProject_nonExistingId_throwsException() {
            given(projectService.getProjectById(999L))
                    .willThrow(new RuntimeException("Project not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(get(BASE_URL + "/{id}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }

        @Test
        @DisplayName("should return 400 when id is not a number")
        void getProject_invalidId_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", "abc"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ───────────────────── GET /api/projects ─────────────────────

    @Nested
    @DisplayName("GET /api/projects")
    class GetAllProjects {

        @Test
        @DisplayName("should return 200 and list of projects")
        void getAllProjects_projectsExist_returnsList() throws Exception {
            ProjectDto proj1 = buildProjectDto(1L, "Atlas", "IN_PROGRESS", Set.of(10L));
            ProjectDto proj2 = buildProjectDto(2L, "Mercury", "PLANNED", Set.of());

            given(projectService.getAllProjects()).willReturn(List.of(proj1, proj2));

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].name").value("Atlas"))
                    .andExpect(jsonPath("$.data[1].name").value("Mercury"));
        }

        @Test
        @DisplayName("should return 200 and empty list when no projects exist")
        void getAllProjects_noProjects_returnsEmptyList() throws Exception {
            given(projectService.getAllProjects()).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ───────────────────── PUT /api/projects/{id} ─────────────────────

    @Nested
    @DisplayName("PUT /api/projects/{id}")
    class UpdateProject {

        @Test
        @DisplayName("should return 200 and updated project when request is valid")
        void updateProject_validRequest_returns200() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setStatus("COMPLETED");

            ProjectDto response = buildProjectDto(PROJECT_ID, "Atlas", "COMPLETED", Set.of(10L, 20L));

            given(projectService.updateProject(eq(PROJECT_ID), any(ProjectDto.class))).willReturn(response);

            mockMvc.perform(put(BASE_URL + "/{id}", PROJECT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Project updated successfully"))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"));

            verify(projectService).updateProject(eq(PROJECT_ID), any(ProjectDto.class));
        }

        @Test
        @DisplayName("should return 400 when project name is blank on update")
        void updateProject_blankName_returns400() throws Exception {
            ProjectDto request = buildCreateRequest();
            request.setName("");

            mockMvc.perform(put(BASE_URL + "/{id}", PROJECT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(projectService, never()).updateProject(any(), any());
        }

        @Test
        @DisplayName("should throw when updating non-existing project")
        void updateProject_nonExistingId_throwsException() {
            ProjectDto request = buildCreateRequest();

            given(projectService.updateProject(eq(999L), any(ProjectDto.class)))
                    .willThrow(new RuntimeException("Project not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(put(BASE_URL + "/{id}", 999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }
    }

    // ───────────────────── DELETE /api/projects/{id} ─────────────────────

    @Nested
    @DisplayName("DELETE /api/projects/{id}")
    class DeleteProject {

        @Test
        @DisplayName("should return 200 when project deleted successfully")
        void deleteProject_existingId_returns200() throws Exception {
            willDoNothing().given(projectService).deleteProject(PROJECT_ID);

            mockMvc.perform(delete(BASE_URL + "/{id}", PROJECT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Project deleted successfully"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(projectService).deleteProject(PROJECT_ID);
        }

        @Test
        @DisplayName("should throw when deleting non-existing project")
        void deleteProject_nonExistingId_throwsException() {
            willThrow(new RuntimeException("Project not found with id: 999"))
                    .given(projectService).deleteProject(999L);

            assertThatThrownBy(() ->
                    mockMvc.perform(delete(BASE_URL + "/{id}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }
    }

    // ───────────────────── POST /api/projects/{projectId}/employees/{employeeId} ─────────────────────

    @Nested
    @DisplayName("POST /api/projects/{projectId}/employees/{employeeId}")
    class AddEmployeeToProject {

        @Test
        @DisplayName("should return 200 and updated project when employee added")
        void addEmployee_validIds_returns200() throws Exception {
            ProjectDto response = buildProjectDto(PROJECT_ID, "Atlas", "IN_PROGRESS", Set.of(EMP_ID));

            given(projectService.addEmployeeToProject(PROJECT_ID, EMP_ID)).willReturn(response);

            mockMvc.perform(post(BASE_URL + "/{projectId}/employees/{employeeId}", PROJECT_ID, EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Employee added to project successfully"))
                    .andExpect(jsonPath("$.data.employeeIds", hasItem(EMP_ID.intValue())));

            verify(projectService).addEmployeeToProject(PROJECT_ID, EMP_ID);
        }

        @Test
        @DisplayName("should throw when project does not exist")
        void addEmployee_nonExistingProject_throwsException() {
            given(projectService.addEmployeeToProject(999L, EMP_ID))
                    .willThrow(new RuntimeException("Project not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(post(BASE_URL + "/{projectId}/employees/{employeeId}", 999L, EMP_ID)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }

        @Test
        @DisplayName("should throw when employee does not exist")
        void addEmployee_nonExistingEmployee_throwsException() {
            given(projectService.addEmployeeToProject(PROJECT_ID, 999L))
                    .willThrow(new RuntimeException("Employee not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(post(BASE_URL + "/{projectId}/employees/{employeeId}", PROJECT_ID, 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Employee not found with id: 999");
        }
    }

    // ───────────────────── DELETE /api/projects/{projectId}/employees/{employeeId} ─────────────────────

    @Nested
    @DisplayName("DELETE /api/projects/{projectId}/employees/{employeeId}")
    class RemoveEmployeeFromProject {

        @Test
        @DisplayName("should return 200 and updated project when employee removed")
        void removeEmployee_validIds_returns200() throws Exception {
            ProjectDto response = buildProjectDto(PROJECT_ID, "Atlas", "IN_PROGRESS", Set.of());

            given(projectService.removeEmployeeFromProject(PROJECT_ID, EMP_ID)).willReturn(response);

            mockMvc.perform(delete(BASE_URL + "/{projectId}/employees/{employeeId}", PROJECT_ID, EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Employee removed from project successfully"))
                    .andExpect(jsonPath("$.data.employeeIds", hasSize(0)));

            verify(projectService).removeEmployeeFromProject(PROJECT_ID, EMP_ID);
        }

        @Test
        @DisplayName("should throw when project does not exist")
        void removeEmployee_nonExistingProject_throwsException() {
            given(projectService.removeEmployeeFromProject(999L, EMP_ID))
                    .willThrow(new RuntimeException("Project not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(delete(BASE_URL + "/{projectId}/employees/{employeeId}", 999L, EMP_ID)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Project not found with id: 999");
        }
    }

    // ───────────────────── GET /api/projects/employee/{employeeId} ─────────────────────

    @Nested
    @DisplayName("GET /api/projects/employee/{employeeId}")
    class GetProjectsByEmployee {

        @Test
        @DisplayName("should return 200 and projects for given employee")
        void getProjectsByEmployee_existingEmployee_returnsList() throws Exception {
            ProjectDto proj1 = buildProjectDto(1L, "Atlas", "IN_PROGRESS", Set.of(EMP_ID));
            ProjectDto proj2 = buildProjectDto(2L, "Mercury", "PLANNED", Set.of(EMP_ID));

            given(projectService.getProjectsByEmployee(EMP_ID)).willReturn(List.of(proj1, proj2));

            mockMvc.perform(get(BASE_URL + "/employee/{employeeId}", EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].name").value("Atlas"))
                    .andExpect(jsonPath("$.data[1].name").value("Mercury"));

            verify(projectService).getProjectsByEmployee(EMP_ID);
        }

        @Test
        @DisplayName("should return 200 and empty list when employee has no projects")
        void getProjectsByEmployee_noProjects_returnsEmptyList() throws Exception {
            given(projectService.getProjectsByEmployee(EMP_ID)).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL + "/employee/{employeeId}", EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("should throw when employee does not exist")
        void getProjectsByEmployee_nonExistingEmployee_throwsException() {
            given(projectService.getProjectsByEmployee(999L))
                    .willThrow(new RuntimeException("Employee not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(get(BASE_URL + "/employee/{employeeId}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Employee not found with id: 999");
        }
    }
}