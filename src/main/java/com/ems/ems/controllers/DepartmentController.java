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
import com.ems.ems.dtos.DepartmentDto;
import com.ems.ems.services.DepartmentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @PostMapping
    public ResponseEntity<ApiResponse<DepartmentDto>> createDepartment(
            @Valid @RequestBody DepartmentDto departmentDto) {
        log.info("POST /api/v1/departments - name: {}", departmentDto.getName());
        DepartmentDto created = departmentService.createDepartment(departmentDto);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Department created successfully", created));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DepartmentDto>> getDepartment(@PathVariable Long id) {
        log.debug("GET /api/v1/departments/{}", id);
        DepartmentDto department = departmentService.getDepartmentById(id);
        return ResponseEntity.ok(ApiResponse.ok(department));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<DepartmentDto>>> getAllDepartments() {
        log.debug("GET /api/v1/departments");
        List<DepartmentDto> departments = departmentService.getAllDepartments();
        return ResponseEntity.ok(ApiResponse.ok(departments));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DepartmentDto>> updateDepartment(
            @PathVariable Long id,
            @Valid @RequestBody DepartmentDto departmentDto) {
        log.info("PUT /api/v1/departments/{}", id);
        DepartmentDto updated = departmentService.updateDepartment(id, departmentDto);
        return ResponseEntity.ok(ApiResponse.ok("Department updated successfully", updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDepartment(@PathVariable Long id) {
        log.info("DELETE /api/v1/departments/{}", id);
        departmentService.deleteDepartment(id);
        return ResponseEntity.ok(ApiResponse.ok("Department deleted successfully", null));
    }
}
