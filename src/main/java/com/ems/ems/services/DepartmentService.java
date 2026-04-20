package com.ems.ems.services;

import java.util.List;

import com.ems.ems.dtos.DepartmentDto;

public interface DepartmentService {

    DepartmentDto createDepartment(DepartmentDto dto);

    DepartmentDto getDepartmentById(Long id);

    List<DepartmentDto> getAllDepartments();

    DepartmentDto updateDepartment(Long id, DepartmentDto dto);

    void deleteDepartment(Long id);
}
