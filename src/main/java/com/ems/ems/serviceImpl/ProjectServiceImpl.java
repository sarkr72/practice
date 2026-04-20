package com.ems.ems.serviceImpl;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.ems.configs.CacheNames;
import com.ems.ems.dtos.ProjectDto;
import com.ems.ems.entities.Employee;
import com.ems.ems.entities.Project;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.repositories.EmployeeRepository;
import com.ems.ems.repositories.ProjectRepository;
import com.ems.ems.services.ProjectService;

@Service
@Transactional(readOnly = true)
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private final ProjectRepository projectRepository;
    private final EmployeeRepository employeeRepository;

    public ProjectServiceImpl(ProjectRepository projectRepository,
                              EmployeeRepository employeeRepository) {
        this.projectRepository = projectRepository;
        this.employeeRepository = employeeRepository;
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.PROJECT,      key = "#result.id") },
        evict = { @CacheEvict(value = CacheNames.PROJECT_LIST, allEntries = true) }
    )
    public ProjectDto createProject(ProjectDto dto) {
        Objects.requireNonNull(dto, "ProjectDto must not be null");
        log.debug("Creating project name={}", dto.getName());
        validateDates(dto);

        Project project = new Project();
        applyDto(project, dto);
        Project saved = projectRepository.save(project);

        log.info("Created project id={} name={}", saved.getId(), saved.getName());
        return toDto(saved);
    }

    @Override
    @Cacheable(value = CacheNames.PROJECT, key = "#id")
    public ProjectDto getProjectById(Long id) {
        Objects.requireNonNull(id, "Project id must not be null");
        log.debug("Fetching project id={}", id);

        Project project = projectRepository.findByIdWithEmployees(id)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Project", id));
        return toDto(project);
    }

    @Override
    @Cacheable(value = CacheNames.PROJECT_LIST, key = "'all'")
    public List<ProjectDto> getAllProjects() {
        log.debug("Fetching all projects");
        return projectRepository.findAllWithEmployees().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.PROJECT,      key = "#id") },
        evict = { @CacheEvict(value = CacheNames.PROJECT_LIST, allEntries = true) }
    )
    public ProjectDto updateProject(Long id, ProjectDto dto) {
        Objects.requireNonNull(id,  "Project id must not be null");
        Objects.requireNonNull(dto, "ProjectDto must not be null");
        log.debug("Updating project id={}", id);
        validateDates(dto);

        Project project = projectRepository.findByIdWithEmployees(id)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Project", id));
        applyDto(project, dto);

        log.info("Updated project id={}", id);
        return toDto(project);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = CacheNames.PROJECT,      key = "#id"),
        @CacheEvict(value = CacheNames.PROJECT_LIST, allEntries = true)
    })
    public void deleteProject(Long id) {
        Objects.requireNonNull(id, "Project id must not be null");
        log.debug("Deleting project id={}", id);

        if (!projectRepository.existsById(id)) {
            throw ResourceNotFoundException.forResource("Project", id);
        }
        projectRepository.deleteById(id);
        log.info("Deleted project id={}", id);
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.PROJECT,      key = "#projectId") },
        evict = { @CacheEvict(value = CacheNames.PROJECT_LIST, allEntries = true) }
    )
    public ProjectDto addEmployeeToProject(Long projectId, Long employeeId) {
        Objects.requireNonNull(projectId,  "Project id must not be null");
        Objects.requireNonNull(employeeId, "Employee id must not be null");
        log.debug("Adding employee id={} to project id={}", employeeId, projectId);

        Project project = projectRepository.findByIdWithEmployees(projectId)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Project", projectId));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Employee", employeeId));

        // Idempotent: Set.add returns false if already a member — treat as a no-op.
        if (project.addEmployee(employee)) {
            log.info("Added employee id={} to project id={}", employeeId, projectId);
        } else {
            log.debug("Employee id={} already assigned to project id={}", employeeId, projectId);
        }
        return toDto(project);
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.PROJECT,      key = "#projectId") },
        evict = { @CacheEvict(value = CacheNames.PROJECT_LIST, allEntries = true) }
    )
    public ProjectDto removeEmployeeFromProject(Long projectId, Long employeeId) {
        Objects.requireNonNull(projectId,  "Project id must not be null");
        Objects.requireNonNull(employeeId, "Employee id must not be null");
        log.debug("Removing employee id={} from project id={}", employeeId, projectId);

        Project project = projectRepository.findByIdWithEmployees(projectId)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Project", projectId));
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Employee", employeeId));

        if (project.removeEmployee(employee)) {
            log.info("Removed employee id={} from project id={}", employeeId, projectId);
        } else {
            log.debug("Employee id={} was not assigned to project id={}", employeeId, projectId);
        }
        return toDto(project);
    }

    @Override
    @Cacheable(value = CacheNames.PROJECT_LIST, key = "'emp:' + #employeeId")
    public List<ProjectDto> getProjectsByEmployee(Long employeeId) {
        Objects.requireNonNull(employeeId, "Employee id must not be null");
        log.debug("Fetching projects for employee id={}", employeeId);

        if (!employeeRepository.existsById(employeeId)) {
            throw ResourceNotFoundException.forResource("Employee", employeeId);
        }
        return projectRepository.findByEmployeeId(employeeId).stream()
                .map(this::toDto)
                .toList();
    }

    // ───────────────────── mapping helpers ─────────────────────

    private void validateDates(ProjectDto dto) {
        if (dto.getStartDate() != null && dto.getEndDate() != null
                && dto.getEndDate().isBefore(dto.getStartDate())) {
            throw new IllegalArgumentException(
                    "Project end date must not be before start date");
        }
    }

    private void applyDto(Project project, ProjectDto dto) {
        project.setName(dto.getName());
        project.setDescription(dto.getDescription());
        project.setStartDate(dto.getStartDate());
        project.setEndDate(dto.getEndDate());
        project.setStatus(dto.getStatus());
        if (dto.getEmployeeIds() != null) {
            List<Employee> employees = employeeRepository.findAllById(dto.getEmployeeIds());

            if (employees.size() != dto.getEmployeeIds().size()) {
                throw new IllegalArgumentException("One or more employee IDs are invalid");
            }
            new java.util.HashSet<>(project.getEmployees()).forEach(project::removeEmployee);
            employees.forEach(project::addEmployee);
        }
    }

    private ProjectDto toDto(Project entity) {
        ProjectDto dto = new ProjectDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setStartDate(entity.getStartDate());
        dto.setEndDate(entity.getEndDate());
        dto.setStatus(entity.getStatus());

        dto.setEmployeeIds(entity.getEmployees().stream()
                .map(Employee::getId)
                .collect(Collectors.toSet()));
        dto.setEmployeeCount(entity.getEmployees().size());
        return dto;
    }
}
