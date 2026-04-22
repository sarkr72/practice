package com.ems.ems.serviceImpl;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.ems.dtos.ProjectDto;
import com.ems.ems.entities.Employee;
import com.ems.ems.entities.Project;
import com.ems.ems.exceptions.DuplicateResourceException;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.repositories.EmployeeRepository;
import com.ems.ems.repositories.ProjectRepository;
import com.ems.ems.services.ProjectService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final EmployeeRepository employeeRepository;

    @Override
    @Transactional
    @CacheEvict(value = "projects", allEntries = true)
    public ProjectDto createProject(ProjectDto dto) {
        if (projectRepository.existsByName(dto.getName())) {
            throw new DuplicateResourceException("Project", "name", dto.getName());
        }

        Project project = new Project();
        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        project.setStartDate(dto.getStartDate());
        project.setEndDate(dto.getEndDate());
        project.setStatus(dto.getStatus());

        if (dto.getEmployeeIds() != null && !dto.getEmployeeIds().isEmpty()) {
            Set<Employee> employees = dto.getEmployeeIds().stream()
                    .map(empId -> employeeRepository.findById(empId)
                            .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", empId)))
                    .collect(Collectors.toSet());
            project.setEmployees(employees);
        }

        Project saved = projectRepository.save(project);
        log.info("Created project with id: {}", saved.getId());
        return mapToDto(saved);
    }

    @Override
    @Cacheable(value = "project", key = "#id")
    public ProjectDto getProjectById(Long id) {
        log.debug("Fetching project with id: {}", id);
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", id));
        return mapToDto(project);
    }

    @Override
    @Cacheable(value = "projects")
    public List<ProjectDto> getAllProjects() {
        log.debug("Fetching all projects");
        return projectRepository.findAll()
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    @CachePut(value = "project", key = "#id")
    @CacheEvict(value = "projects", allEntries = true)
    public ProjectDto updateProject(Long id, ProjectDto dto) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", id));

        if (!project.getName().equals(dto.getName()) && projectRepository.existsByName(dto.getName())) {
            throw new DuplicateResourceException("Project", "name", dto.getName());
        }

        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        project.setStartDate(dto.getStartDate());
        project.setEndDate(dto.getEndDate());
        project.setStatus(dto.getStatus());

        if (dto.getEmployeeIds() != null) {
            Set<Employee> employees = dto.getEmployeeIds().stream()
                    .map(empId -> employeeRepository.findById(empId)
                            .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", empId)))
                    .collect(Collectors.toSet());
            project.setEmployees(employees);
        }

        Project updated = projectRepository.save(project);
        log.info("Updated project with id: {}", id);
        return mapToDto(updated);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"project", "projects"}, allEntries = true)
    public void deleteProject(Long id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", id));
        projectRepository.delete(project);
        log.info("Deleted project with id: {}", id);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"project", "projects"}, allEntries = true)
    public ProjectDto addEmployeeToProject(Long projectId, Long employeeId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", employeeId));

        project.getEmployees().add(employee);
        Project updated = projectRepository.save(project);
        log.info("Added employee {} to project {}", employeeId, projectId);
        return mapToDto(updated);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"project", "projects"}, allEntries = true)
    public ProjectDto removeEmployeeFromProject(Long projectId, Long employeeId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project", "id", projectId));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", employeeId));

        project.getEmployees().remove(employee);
        Project updated = projectRepository.save(project);
        log.info("Removed employee {} from project {}", employeeId, projectId);
        return mapToDto(updated);
    }

    @Override
    public List<ProjectDto> getProjectsByEmployee(Long employeeId) {
        if (!employeeRepository.existsById(employeeId)) {
            throw new ResourceNotFoundException("Employee", "id", employeeId);
        }
        return projectRepository.findByEmployeeId(employeeId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    private ProjectDto mapToDto(Project p) {
        Set<Long> employeeIds = p.getEmployees().stream()
                .map(Employee::getId)
                .collect(Collectors.toSet());

        return ProjectDto.builder()
                .id(p.getId())
                .name(p.getName())
                .description(p.getDescription())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .status(p.getStatus())
                .employeeIds(employeeIds)
                .employeeCount(employeeIds.size())
                .build();
    }
}