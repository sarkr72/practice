package com.ems.ems.services;

import java.util.List;

import com.ems.ems.dtos.EmployeeDto;

public interface EmployeeService {

    EmployeeDto createEmployee(EmployeeDto employeeDto);

    EmployeeDto getEmployeeById(Long id);

    List<EmployeeDto> getAllEmployees();

    EmployeeDto updateEmployee(Long id, EmployeeDto employeeDto);

    void deleteEmployee(Long id);

    List<EmployeeDto> getEmployeesByDepartment(Long departmentId);

    List<EmployeeDto> searchEmployees(String query);
}
