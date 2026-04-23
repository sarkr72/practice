package com.ems.ems.serviceImpl;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.ems.dtos.EmployeeDto;
import com.ems.ems.entities.Department;
import com.ems.ems.entities.Employee;
import com.ems.ems.exceptions.DuplicateResourceException;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.repositories.DepartmentRepository;
import com.ems.ems.repositories.EmployeeRepository;
import com.ems.ems.services.EmployeeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;

    @Override
    @Transactional
    @CacheEvict(value = "employees", allEntries = true)
    public EmployeeDto createEmployee(EmployeeDto dto) {
        if (employeeRepository.existsByEmail(dto.getEmail())) {
            throw new DuplicateResourceException("Employee", "email", dto.getEmail());
        }

        Employee employee = mapToEntity(dto);

        if (dto.getDepartmentId() != null) {
            Department dept = departmentRepository.findById(dto.getDepartmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Department", "id", dto.getDepartmentId()));
            employee.setDepartment(dept);
        }

        Employee saved = employeeRepository.save(employee);
        log.info("Created employee with id: {}", saved.getId());
        return mapToDto(saved);
    }

    @Override
    @Cacheable(value = "employee", key = "#id")
    // Narrow retry: only transient DB issues (deadlocks, connection blips).
    // Do NOT retry business exceptions like ResourceNotFoundException.
    @Retryable(
            retryFor = { TransientDataAccessException.class, CannotAcquireLockException.class },
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2.0, maxDelay = 2000))
    public EmployeeDto getEmployeeById(Long id) {
        log.debug("Fetching employee with id: {}", id);
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", id));
        return mapToDto(employee);
    }

    @Override
    @Cacheable(value = "employees")
    public List<EmployeeDto> getAllEmployees() {
        log.debug("Fetching all employees");
        return employeeRepository.findAll()
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    @CachePut(value = "employee", key = "#id")
    @CacheEvict(value = "employees", allEntries = true)
    public EmployeeDto updateEmployee(Long id, EmployeeDto dto) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", id));

        if (!employee.getEmail().equals(dto.getEmail())
                && employeeRepository.existsByEmail(dto.getEmail())) {
            throw new DuplicateResourceException("Employee", "email", dto.getEmail());
        }

        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getLastName());
        employee.setEmail(dto.getEmail());
        employee.setPhone(dto.getPhone());
        employee.setJobTitle(dto.getJobTitle());
        employee.setSalary(dto.getSalary());
        employee.setHireDate(dto.getHireDate());
        if (dto.getActive() != null) {
            employee.setActive(dto.getActive());
        }

        if (dto.getDepartmentId() != null) {
            Department dept = departmentRepository.findById(dto.getDepartmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Department", "id", dto.getDepartmentId()));
            employee.setDepartment(dept);
        } else {
            employee.setDepartment(null);
        }

        Employee updated = employeeRepository.save(employee);
        log.info("Updated employee with id: {}", id);
        return mapToDto(updated);
    }

    @Override
    @Transactional
    @CacheEvict(value = {"employee", "employees"}, allEntries = true)
    public void deleteEmployee(Long id) {
        Employee employee = employeeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", id));
        employeeRepository.delete(employee);
        log.info("Deleted employee with id: {}", id);
    }

    @Override
    public List<EmployeeDto> getEmployeesByDepartment(Long departmentId) {
        if (!departmentRepository.existsById(departmentId)) {
            throw new ResourceNotFoundException("Department", "id", departmentId);
        }
        return employeeRepository.findByDepartmentId(departmentId)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<EmployeeDto> searchEmployees(String query) {
        return employeeRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(query, query)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    // ---- Mapping helpers ----

    private EmployeeDto mapToDto(Employee e) {
        return EmployeeDto.builder()
                .id(e.getId())
                .firstName(e.getFirstName())
                .lastName(e.getLastName())
                .email(e.getEmail())
                .phone(e.getPhone())
                .jobTitle(e.getJobTitle())
                .salary(e.getSalary())
                .hireDate(e.getHireDate())
                .active(e.getActive())
                .departmentId(e.getDepartment() != null ? e.getDepartment().getId() : null)
                .departmentName(e.getDepartment() != null ? e.getDepartment().getName() : null)
                .build();
    }

    private Employee mapToEntity(EmployeeDto dto) {
        Employee employee = new Employee();
        employee.setFirstName(dto.getFirstName());
        employee.setLastName(dto.getLastName());
        employee.setEmail(dto.getEmail());
        employee.setPhone(dto.getPhone());
        employee.setJobTitle(dto.getJobTitle());
        employee.setSalary(dto.getSalary());
        employee.setHireDate(dto.getHireDate());
        employee.setActive(dto.getActive() != null ? dto.getActive() : Boolean.TRUE);
        return employee;
    }
}
