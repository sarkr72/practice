package com.ems.ems.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ems.ems.dtos.ApiResponse;
import com.ems.ems.dtos.ProjectDto;
import com.ems.ems.services.ProjectService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectDto>> createProject(
            @Valid @RequestBody ProjectDto projectDto) {
        log.info("POST /api/projects - name: {}", projectDto.getName());
        ProjectDto created = projectService.createProject(projectDto);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Project created successfully", created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectDto>> getProject(@PathVariable Long id) {
        log.debug("GET /api/projects/{}", id);
        ProjectDto project = projectService.getProjectById(id);
        return ResponseEntity.ok(ApiResponse.ok(project));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectDto>>> getAllProjects() {
        log.debug("GET /api/projects");
        List<ProjectDto> projects = projectService.getAllProjects();
        return ResponseEntity.ok(ApiResponse.ok(projects));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectDto>> updateProject(
            @PathVariable Long id,
            @Valid @RequestBody ProjectDto projectDto) {
        log.info("PUT /api/projects/{}", id);
        ProjectDto updated = projectService.updateProject(id, projectDto);
        return ResponseEntity.ok(ApiResponse.ok("Project updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProject(@PathVariable Long id) {
        log.info("DELETE /api/projects/{}", id);
        projectService.deleteProject(id);
        return ResponseEntity.ok(ApiResponse.ok("Project deleted successfully", null));
    }

    @PostMapping("/{projectId}/employees/{employeeId}")
    public ResponseEntity<ApiResponse<ProjectDto>> addEmployee(
            @PathVariable Long projectId,
            @PathVariable Long employeeId) {
        log.info("POST /api/projects/{}/employees/{}", projectId, employeeId);
        ProjectDto updated = projectService.addEmployeeToProject(projectId, employeeId);
        return ResponseEntity.ok(ApiResponse.ok("Employee added to project successfully", updated));
    }

    @DeleteMapping("/{projectId}/employees/{employeeId}")
    public ResponseEntity<ApiResponse<ProjectDto>> removeEmployee(
            @PathVariable Long projectId,
            @PathVariable Long employeeId) {
        log.info("DELETE /api/projects/{}/employees/{}", projectId, employeeId);
        ProjectDto updated = projectService.removeEmployeeFromProject(projectId, employeeId);
        return ResponseEntity.ok(ApiResponse.ok("Employee removed from project successfully", updated));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<ApiResponse<List<ProjectDto>>> getProjectsByEmployee(
            @PathVariable Long employeeId) {
        log.debug("GET /api/projects/employee/{}", employeeId);
        List<ProjectDto> projects = projectService.getProjectsByEmployee(employeeId);
        return ResponseEntity.ok(ApiResponse.ok(projects));
    }
}