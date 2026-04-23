package com.ems.ems.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.ems.ems.entities.Department;
import com.ems.ems.entities.Employee;

@DataJpaTest
@AutoConfigureTestDatabase
@DisplayName("EmployeeRepository")
class EmployeeRepositoryTest {

    @Autowired
    private EmployeeRepository employeeRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    private Department engineering;

    @BeforeEach
    void setUp() {
        engineering = new Department();
        engineering.setName("Engineering");
        engineering.setDescription("dept");
        engineering = departmentRepository.saveAndFlush(engineering);
    }

    private Employee newEmployee(String first, String last, String email) {
        Employee e = new Employee();
        e.setFirstName(first);
        e.setLastName(last);
        e.setEmail(email);
        e.setSalary(new BigDecimal("95000.00"));
        e.setHireDate(LocalDate.of(2024, 3, 15));
        e.setActive(Boolean.TRUE);
        e.setDepartment(engineering);
        return e;
    }

    @Test
    @DisplayName("existsByEmail returns true for existing email")
    void existsByEmail_existing_returnsTrue() {
        employeeRepository.saveAndFlush(newEmployee("John", "Doe", "john@ems.com"));

        assertThat(employeeRepository.existsByEmail("john@ems.com")).isTrue();
        assertThat(employeeRepository.existsByEmail("nobody@ems.com")).isFalse();
    }

    @Test
    @DisplayName("findByDepartmentId returns only employees in that department")
    void findByDepartmentId_returnsScoped() {
        Department finance = new Department();
        finance.setName("Finance");
        finance.setDescription("fin");
        finance = departmentRepository.saveAndFlush(finance);

        employeeRepository.saveAndFlush(newEmployee("Alice", "Smith", "alice@ems.com"));
        Employee bob = newEmployee("Bob", "Jones", "bob@ems.com");
        bob.setDepartment(finance);
        employeeRepository.saveAndFlush(bob);

        List<Employee> engEmployees = employeeRepository.findByDepartmentId(engineering.getId());
        assertThat(engEmployees).hasSize(1)
                .extracting(Employee::getEmail)
                .containsExactly("alice@ems.com");
    }

    @Test
    @DisplayName("search by first or last name is case-insensitive and partial")
    void search_isCaseInsensitivePartial() {
        employeeRepository.saveAndFlush(newEmployee("Jonathan", "Smith", "jsmith@ems.com"));
        employeeRepository.saveAndFlush(newEmployee("Alice", "Johnson", "ajohnson@ems.com"));

        List<Employee> results = employeeRepository
                .findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase("john", "john");

        assertThat(results)
                .hasSize(2)
                .extracting(Employee::getEmail)
                .containsExactlyInAnyOrder("jsmith@ems.com", "ajohnson@ems.com");
    }

    @Test
    @DisplayName("salary precision is preserved (BigDecimal, not Double)")
    void salaryPrecisionIsPreserved() {
        Employee e = newEmployee("Pat", "Penny", "pat@ems.com");
        e.setSalary(new BigDecimal("12345.67"));
        employeeRepository.saveAndFlush(e);

        Employee loaded = employeeRepository.findById(e.getId()).orElseThrow();
        assertThat(loaded.getSalary()).isEqualByComparingTo("12345.67");
    }
}
