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
import com.ems.ems.dtos.EmployeeDto;
import com.ems.ems.entities.Department;
import com.ems.ems.entities.Employee;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.repositories.DepartmentRepository;
import com.ems.ems.repositories.EmployeeRepository;
import com.ems.ems.services.EmployeeService;

@Service
@Transactional(readOnly = true)
public class EmployeeServiceImpl implements EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeServiceImpl.class);

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;

    public EmployeeServiceImpl(EmployeeRepository employeeRepository,
                               DepartmentRepository departmentRepository) {
        this.employeeRepository = employeeRepository;
        this.departmentRepository = departmentRepository;
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.EMPLOYEE,        key = "#result.id") },
        evict = {
            @CacheEvict(value = CacheNames.EMPLOYEE_LIST,   allEntries = true),
            @CacheEvict(value = CacheNames.EMPLOYEE_SEARCH, allEntries = true)
        }
    )
    public EmployeeDto createEmployee(EmployeeDto dto) {
        Objects.requireNonNull(dto, "EmployeeDto must not be null");
        log.debug("Creating employee email={}", dto.getEmail());

        if (employeeRepository.existsByEmailIgnoreCase(dto.getEmail())) {
            throw new IllegalArgumentException(
                    "Employee already exists with email: " + dto.getEmail());
        }

        Employee employee = new Employee();
        applyDto(employee, dto);
        Employee saved = employeeRepository.save(employee);

        log.info("Created employee id={} email={}", saved.getId(), saved.getEmail());
        return toDto(saved);
    }

    @Override
    @Cacheable(value = CacheNames.EMPLOYEE, key = "#id")
    public EmployeeDto getEmployeeById(Long id) {
        Objects.requireNonNull(id, "Employee id must not be null");
        log.debug("Fetching employee id={}", id);

        Employee employee = employeeRepository.findByIdWithDepartment(id)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Employee", id));
        return toDto(employee);
    }

    @Override
    @Cacheable(value = CacheNames.EMPLOYEE_LIST, key = "'all'")
    public List<EmployeeDto> getAllEmployees() {
        log.debug("Fetching all employees");
        return employeeRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional
    @Caching(
        put   = { @CachePut (value = CacheNames.EMPLOYEE,        key = "#id") },
        evict = {
            @CacheEvict(value = CacheNames.EMPLOYEE_LIST,   allEntries = true),
            @CacheEvict(value = CacheNames.EMPLOYEE_SEARCH, allEntries = true)
        }
    )
    public EmployeeDto updateEmployee(Long id, EmployeeDto dto) {
        Objects.requireNonNull(id,  "Employee id must not be null");
        Objects.requireNonNull(dto, "EmployeeDto must not be null");
        log.debug("Updating employee id={}", id);

        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.forResource("Employee", id));

        // Guard against changing email to one already held by a different employee.
        if (!employee.getEmail().equalsIgnoreCase(dto.getEmail())
                && employeeRepository.existsByEmailIgnoreCase(dto.getEmail())) {
            throw new IllegalArgumentException(
                    "Employee already exists with email: " + dto.getEmail());
        }

        applyDto(employee, dto);
        log.info("Updated employee id={}", id);
        return toDto(employee);
    }

    @Override
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = CacheNames.EMPLOYEE,        key = "#id"),
        @CacheEvict(value = CacheNames.EMPLOYEE_LIST,   allEntries = true),
        @CacheEvict(value = CacheNames.EMPLOYEE_SEARCH, allEntries = true)
    })
    public void deleteEmployee(Long id) {
        Objects.requireNonNull(id, "Employee id must not be null");
        log.debug("Deleting employee id={}", id);

        if (!employeeRepository.existsById(id)) {
            throw ResourceNotFoundException.forResource("Employee", id);
        }
        employeeRepository.deleteById(id);
        log.info("Deleted employee id={}", id);
    }

    @Override
    @Cacheable(value = CacheNames.EMPLOYEE_LIST, key = "'dept:' + #departmentId")
    public List<EmployeeDto> getEmployeesByDepartment(Long departmentId) {
        Objects.requireNonNull(departmentId, "Department id must not be null");
        log.debug("Fetching employees for department id={}", departmentId);

        if (!departmentRepository.existsById(departmentId)) {
            throw ResourceNotFoundException.forResource("Department", departmentId);
        }
        return employeeRepository.findByDepartmentId(departmentId).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Cacheable(value = CacheNames.EMPLOYEE_SEARCH, key = "'q:' + #query")
    public List<EmployeeDto> searchEmployees(String query) {
        log.debug("Searching employees query={}", query);

        if (query == null || query.isBlank()) {
            return List.of();
        }
        return employeeRepository.search(query.trim()).stream()
                .map(this::toDto)
                .toList();
    }

    // ───────────────────── mapping helpers ─────────────────────

    private void applyDto(Employee employee, EmployeeDto dto) {
        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getLastName());
        employee.setEmail(dto.getEmail());
        employee.setPhone(dto.getPhone());
        employee.setJobTitle(dto.getJobTitle());
        employee.setSalary(dto.getSalary());
        employee.setHireDate(dto.getHireDate());
        // Default to active when caller omits the flag; otherwise honor it.
        employee.setActive(dto.getActive() == null ? Boolean.TRUE : dto.getActive());

        if (dto.getDepartmentId() != null) {
            Department department = departmentRepository.findById(dto.getDepartmentId())
                    .orElseThrow(() -> ResourceNotFoundException.forResource(
                            "Department", dto.getDepartmentId()));
            employee.setDepartment(department);
        } else {
            employee.setDepartment(null);
        }
    }

    private EmployeeDto toDto(Employee entity) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(entity.getId());
        dto.setFirstName(entity.getFirstName());
        dto.setLastName(entity.getLastName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setJobTitle(entity.getJobTitle());
        dto.setSalary(entity.getSalary());
        dto.setHireDate(entity.getHireDate());
        dto.setActive(entity.isActive());

        if (entity.getDepartment() != null) {
            dto.setDepartmentId(entity.getDepartment().getId());
            dto.setDepartmentName(entity.getDepartment().getName());
        }
        return dto;
    }
}
