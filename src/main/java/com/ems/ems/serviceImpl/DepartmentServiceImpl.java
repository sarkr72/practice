package com.ems.ems.serviceImpl;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.ems.configs.CacheNames;
import com.ems.ems.dtos.DepartmentDto;
import com.ems.ems.entities.Department;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.repositories.DepartmentRepository;
import com.ems.ems.services.DepartmentService;

@Service
@Transactional(readOnly = true)
public class DepartmentServiceImpl implements DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentServiceImpl.class);

    private final DepartmentRepository departmentRepository;

    public DepartmentServiceImpl(DepartmentRepository departmentRepository) {
        this.departmentRepository = departmentRepository;
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.DEPARTMENT,      key = "#result.id") },
        evict = { @CacheEvict(value = CacheNames.DEPARTMENT_LIST, allEntries = true) }
    )
    public DepartmentDto createDepartment(DepartmentDto dto) {
        Objects.requireNonNull(dto, "DepartmentDto must not be null");
        log.debug("Creating department name={}", dto.getName());

        if (departmentRepository.existsByNameIgnoreCase(dto.getName())) {
            throw new IllegalArgumentException(
                    "Department already exists with name: " + dto.getName());
        }

        Department saved = departmentRepository.save(
                new Department(dto.getName(), dto.getDescription()));

        log.info("Created department id={} name={}", saved.getId(), saved.getName());
        return toDto(saved);
    }

    @Override
    @Cacheable(value = CacheNames.DEPARTMENT, key = "#id")
    public DepartmentDto getDepartmentById(Long id) {
        Objects.requireNonNull(id, "Department id must not be null");
        log.debug("Fetching department id={}", id);

        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Department", id));
        return toDto(department);
    }

    @Override
    @Cacheable(value = CacheNames.DEPARTMENT_LIST, key = "'all'")
    public List<DepartmentDto> getAllDepartments() {
        log.debug("Fetching all departments");
        return departmentRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.DEPARTMENT,      key = "#id") },
        evict = { @CacheEvict(value = CacheNames.DEPARTMENT_LIST, allEntries = true) }
    )
    public DepartmentDto updateDepartment(Long id, DepartmentDto dto) {
        Objects.requireNonNull(id,  "Department id must not be null");
        Objects.requireNonNull(dto, "DepartmentDto must not be null");
        log.debug("Updating department id={}", id);

        Department department = departmentRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Department", id));

        // Guard against renaming into an existing department's name.
        if (!department.getName().equalsIgnoreCase(dto.getName())
                && departmentRepository.existsByNameIgnoreCase(dto.getName())) {
            throw new IllegalArgumentException(
                    "Department already exists with name: " + dto.getName());
        }

        department.setName(dto.getName());
        department.setDescription(dto.getDescription());

        // Managed entity — JPA dirty checking flushes at commit.
        log.info("Updated department id={}", id);
        return toDto(department);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = CacheNames.DEPARTMENT,      key = "#id"),
        @CacheEvict(value = CacheNames.DEPARTMENT_LIST, allEntries = true)
    })
    public void deleteDepartment(Long id) {
        Objects.requireNonNull(id, "Department id must not be null");
        log.debug("Deleting department id={}", id);

        if (!departmentRepository.existsById(id)) {
            throw ResourceNotFoundException.forResource("Department", id);
        }
        departmentRepository.deleteById(id);
        log.info("Deleted department id={}", id);
    }

    // ───────────────────── mapping helpers ─────────────────────

    private DepartmentDto toDto(Department entity) {
        DepartmentDto dto = new DepartmentDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        // NOTE: N+1 risk inside getAllDepartments(). Consider a projection
        // query that returns (id, name, description, employeeCount) in one hit.
        dto.setEmployeeCount(entity.getEmployees() != null ? entity.getEmployees().size() : 0);
        return dto;
    }
}
