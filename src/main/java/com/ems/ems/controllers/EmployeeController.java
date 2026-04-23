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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ems.ems.dtos.ApiResponse;
import com.ems.ems.dtos.EmployeeDto;
import com.ems.ems.services.EmployeeService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/employees")
@RequiredArgsConstructor
public class EmployeeController {

    private final EmployeeService employeeService;

    @PostMapping
    public ResponseEntity<ApiResponse<EmployeeDto>> createEmployee(
            @Valid @RequestBody EmployeeDto employeeDto) {
        log.info("POST /api/v1/employees - email: {}", employeeDto.getEmail());
        EmployeeDto created = employeeService.createEmployee(employeeDto);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Employee created successfully", created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeDto>> getEmployee(@PathVariable Long id) {
        log.debug("GET /api/v1/employees/{}", id);
        EmployeeDto employee = employeeService.getEmployeeById(id);
        return ResponseEntity.ok(ApiResponse.ok(employee));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmployeeDto>>> getAllEmployees() {
        log.debug("GET /api/v1/employees");
        List<EmployeeDto> employees = employeeService.getAllEmployees();
        return ResponseEntity.ok(ApiResponse.ok(employees));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeDto>> updateEmployee(
            @PathVariable Long id,
            @Valid @RequestBody EmployeeDto employeeDto) {
        log.info("PUT /api/v1/employees/{}", id);
        EmployeeDto updated = employeeService.updateEmployee(id, employeeDto);
        return ResponseEntity.ok(ApiResponse.ok("Employee updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEmployee(@PathVariable Long id) {
        log.info("DELETE /api/v1/employees/{}", id);
        employeeService.deleteEmployee(id);
        return ResponseEntity.ok(ApiResponse.ok("Employee deleted successfully", null));
    }

    @GetMapping("/department/{departmentId}")
    public ResponseEntity<ApiResponse<List<EmployeeDto>>> getByDepartment(
            @PathVariable Long departmentId) {
        log.debug("GET /api/v1/employees/department/{}", departmentId);
        List<EmployeeDto> employees = employeeService.getEmployeesByDepartment(departmentId);
        return ResponseEntity.ok(ApiResponse.ok(employees));
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<EmployeeDto>>> searchEmployees(
            @RequestParam String query) {
        log.debug("GET /api/v1/employees/search?query={}", query);
        List<EmployeeDto> employees = employeeService.searchEmployees(query);
        return ResponseEntity.ok(ApiResponse.ok(employees));
    }
}
