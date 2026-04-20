package com.ems.ems.services;

import java.util.List;

import com.ems.ems.dtos.ProjectDto;

public interface ProjectService {

    ProjectDto createProject(ProjectDto dto);

    ProjectDto getProjectById(Long id);

    List<ProjectDto> getAllProjects();

    ProjectDto updateProject(Long id, ProjectDto dto);

    void deleteProject(Long id);

    ProjectDto addEmployeeToProject(Long projectId, Long employeeId);

    ProjectDto removeEmployeeFromProject(Long projectId, Long employeeId);

    List<ProjectDto> getProjectsByEmployee(Long employeeId);
}
