package com.ems.ems.services;

import java.util.List;

import com.ems.ems.dtos.EmployeeDto;

public interface EmployeeService {

    EmployeeDto createEmployee(EmployeeDto dto);

    EmployeeDto getEmployeeById(Long id);

    List<EmployeeDto> getAllEmployees();

    EmployeeDto updateEmployee(Long id, EmployeeDto dto);

    void deleteEmployee(Long id);

    List<EmployeeDto> getEmployeesByDepartment(Long departmentId);

    List<EmployeeDto> searchEmployees(String query);
}
