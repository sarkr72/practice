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

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectDto>> createProject(
            @Valid @RequestBody ProjectDto request) {
        ProjectDto created = projectService.createProject(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Project created successfully", created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectDto>> getProjectById(@PathVariable Long id) {
        ProjectDto project = projectService.getProjectById(id);
        return ResponseEntity.ok(ApiResponse.success("Project retrieved successfully", project));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ProjectDto>>> getAllProjects() {
        List<ProjectDto> projects = projectService.getAllProjects();
        return ResponseEntity.ok(ApiResponse.success("Projects retrieved successfully", projects));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProjectDto>> updateProject(
            @PathVariable Long id,
            @Valid @RequestBody ProjectDto request) {
        ProjectDto updated = projectService.updateProject(id, request);
        return ResponseEntity.ok(ApiResponse.success("Project updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteProject(@PathVariable Long id) {
        projectService.deleteProject(id);
        return ResponseEntity.ok(ApiResponse.success("Project deleted successfully"));
    }

    @PostMapping("/{projectId}/employees/{employeeId}")
    public ResponseEntity<ApiResponse<ProjectDto>> addEmployeeToProject(
            @PathVariable Long projectId,
            @PathVariable Long employeeId) {
        ProjectDto project = projectService.addEmployeeToProject(projectId, employeeId);
        return ResponseEntity.ok(ApiResponse.success("Employee added to project successfully", project));
    }

    @DeleteMapping("/{projectId}/employees/{employeeId}")
    public ResponseEntity<ApiResponse<ProjectDto>> removeEmployeeFromProject(
            @PathVariable Long projectId,
            @PathVariable Long employeeId) {
        ProjectDto project = projectService.removeEmployeeFromProject(projectId, employeeId);
        return ResponseEntity.ok(ApiResponse.success("Employee removed from project successfully", project));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<ApiResponse<List<ProjectDto>>> getProjectsByEmployee(
            @PathVariable Long employeeId) {
        List<ProjectDto> projects = projectService.getProjectsByEmployee(employeeId);
        return ResponseEntity.ok(ApiResponse.success("Projects retrieved successfully", projects));
    }
}
