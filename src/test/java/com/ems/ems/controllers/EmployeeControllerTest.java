package com.ems.ems.controllers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.ems.ems.dtos.EmployeeDto;
import com.ems.ems.services.EmployeeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.servlet.ServletException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeController")
class EmployeeControllerTest {

    private static final String BASE_URL = "/api/employees";
    private static final Long EMP_ID = 1L;
    private static final Long DEPT_ID = 10L;

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Mock
    private EmployeeService employeeService;

    @InjectMocks
    private EmployeeController employeeController;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(employeeController).build();
    }

    // ───────────────────── test data builders ─────────────────────

    private EmployeeDto buildEmployeeDto(Long id, String firstName, String lastName, String email) {
        EmployeeDto dto = new EmployeeDto();
        dto.setId(id);
        dto.setFirstName(firstName);
        dto.setLastName(lastName);
        dto.setEmail(email);
        dto.setPhone("555-0100");
        dto.setJobTitle("Software Engineer");
        dto.setSalary(BigDecimal.valueOf(95000.0));
        dto.setHireDate(LocalDate.of(2024, 3, 15));
        dto.setActive(true);
        dto.setDepartmentId(DEPT_ID);
        dto.setDepartmentName("Engineering");
        return dto;
    }

    private EmployeeDto buildDefaultEmployee() {
        return buildEmployeeDto(EMP_ID, "John", "Doe", "john.doe@ems.com");
    }

    private EmployeeDto buildCreateRequest() {
        EmployeeDto dto = new EmployeeDto();
        dto.setFirstName("John");
        dto.setLastName("Doe");
        dto.setEmail("john.doe@ems.com");
        dto.setPhone("555-0100");
        dto.setJobTitle("Software Engineer");
        dto.setSalary(BigDecimal.valueOf(95000.0));
        dto.setHireDate(LocalDate.of(2024, 3, 15));
        dto.setDepartmentId(DEPT_ID);
        return dto;
    }

    // ───────────────────── POST /api/employees ─────────────────────

    @Nested
    @DisplayName("POST /api/employees")
    class CreateEmployee {

        @Test
        @DisplayName("should return 201 and created employee when request is valid")
        void createEmployee_validRequest_returns201() throws Exception {
            EmployeeDto request = buildCreateRequest();
            EmployeeDto response = buildDefaultEmployee();

            given(employeeService.createEmployee(any(EmployeeDto.class))).willReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Employee created successfully"))
                    .andExpect(jsonPath("$.data.id").value(EMP_ID))
                    .andExpect(jsonPath("$.data.firstName").value("John"))
                    .andExpect(jsonPath("$.data.lastName").value("Doe"))
                    .andExpect(jsonPath("$.data.email").value("john.doe@ems.com"))
                    .andExpect(jsonPath("$.data.salary").value(95000.0))
                    .andExpect(jsonPath("$.data.departmentId").value(DEPT_ID))
                    .andExpect(jsonPath("$.timestamp").exists());

            verify(employeeService).createEmployee(any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when first name is blank")
        void createEmployee_blankFirstName_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setFirstName("");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(employeeService, never()).createEmployee(any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when last name is null")
        void createEmployee_nullLastName_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setLastName(null);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(employeeService, never()).createEmployee(any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when email is blank")
        void createEmployee_blankEmail_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setEmail("");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(employeeService, never()).createEmployee(any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when email format is invalid")
        void createEmployee_invalidEmail_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setEmail("not-an-email");

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(employeeService, never()).createEmployee(any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when salary is negative")
        void createEmployee_negativeSalary_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setSalary(BigDecimal.valueOf(-50000.0));

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(employeeService, never()).createEmployee(any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when salary is zero")
        void createEmployee_zeroSalary_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setSalary(BigDecimal.valueOf(0.0));

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(employeeService, never()).createEmployee(any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when multiple fields fail validation")
        void createEmployee_multipleViolations_returns400() throws Exception {
            EmployeeDto request = new EmployeeDto();
            request.setFirstName("");
            request.setLastName("");
            request.setEmail("bad");
            request.setSalary(BigDecimal.valueOf(-1.0));

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 when request body is missing")
        void createEmployee_noBody_returns400() throws Exception {
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should accept request when optional salary field is null")
        void createEmployee_nullSalary_returns201() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setSalary(null);
            EmployeeDto response = buildDefaultEmployee();
            response.setSalary(null);

            given(employeeService.createEmployee(any(EmployeeDto.class))).willReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true));
        }
    }

    // ───────────────────── GET /api/employees/{id} ─────────────────────

    @Nested
    @DisplayName("GET /api/employees/{id}")
    class GetEmployeeById {

        @Test
        @DisplayName("should return 200 and employee when found")
        void getEmployee_existingId_returns200() throws Exception {
            EmployeeDto employee = buildDefaultEmployee();

            given(employeeService.getEmployeeById(EMP_ID)).willReturn(employee);

            mockMvc.perform(get(BASE_URL + "/{id}", EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.id").value(EMP_ID))
                    .andExpect(jsonPath("$.data.firstName").value("John"))
                    .andExpect(jsonPath("$.data.email").value("john.doe@ems.com"))
                    .andExpect(jsonPath("$.data.departmentName").value("Engineering"));

            verify(employeeService).getEmployeeById(EMP_ID);
        }

        @Test
        @DisplayName("should throw when employee not found")
        void getEmployee_nonExistingId_throwsException() {
            given(employeeService.getEmployeeById(999L))
                    .willThrow(new RuntimeException("Employee not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(get(BASE_URL + "/{id}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Employee not found with id: 999");
        }

        @Test
        @DisplayName("should return 400 when id is not a number")
        void getEmployee_invalidId_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/{id}", "xyz"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ───────────────────── GET /api/employees ─────────────────────

    @Nested
    @DisplayName("GET /api/employees")
    class GetAllEmployees {

        @Test
        @DisplayName("should return 200 and list of employees")
        void getAllEmployees_employeesExist_returnsList() throws Exception {
            EmployeeDto emp1 = buildEmployeeDto(1L, "John", "Doe", "john@ems.com");
            EmployeeDto emp2 = buildEmployeeDto(2L, "Jane", "Smith", "jane@ems.com");

            given(employeeService.getAllEmployees()).willReturn(List.of(emp1, emp2));

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[0].firstName").value("John"))
                    .andExpect(jsonPath("$.data[1].firstName").value("Jane"));
        }

        @Test
        @DisplayName("should return 200 and empty list when no employees exist")
        void getAllEmployees_noEmployees_returnsEmptyList() throws Exception {
            given(employeeService.getAllEmployees()).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    // ───────────────────── PUT /api/employees/{id} ─────────────────────

    @Nested
    @DisplayName("PUT /api/employees/{id}")
    class UpdateEmployee {

        @Test
        @DisplayName("should return 200 and updated employee when request is valid")
        void updateEmployee_validRequest_returns200() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setJobTitle("Senior Software Engineer");
            request.setSalary(BigDecimal.valueOf(130000.0));

            EmployeeDto response = buildDefaultEmployee();
            response.setJobTitle("Senior Software Engineer");
            response.setSalary(BigDecimal.valueOf(130000.0));

            given(employeeService.updateEmployee(eq(EMP_ID), any(EmployeeDto.class))).willReturn(response);

            mockMvc.perform(put(BASE_URL + "/{id}", EMP_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Employee updated successfully"))
                    .andExpect(jsonPath("$.data.jobTitle").value("Senior Software Engineer"))
                    .andExpect(jsonPath("$.data.salary").value(130000.0));

            verify(employeeService).updateEmployee(eq(EMP_ID), any(EmployeeDto.class));
        }

        @Test
        @DisplayName("should return 400 when email is invalid on update")
        void updateEmployee_invalidEmail_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setEmail("invalid");

            mockMvc.perform(put(BASE_URL + "/{id}", EMP_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());

            verify(employeeService, never()).updateEmployee(any(), any());
        }

        @Test
        @DisplayName("should throw when updating non-existing employee")
        void updateEmployee_nonExistingId_throwsException() {
            EmployeeDto request = buildCreateRequest();

            given(employeeService.updateEmployee(eq(999L), any(EmployeeDto.class)))
                    .willThrow(new RuntimeException("Employee not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(put(BASE_URL + "/{id}", 999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request))))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Employee not found with id: 999");
        }
    }

    // ───────────────────── DELETE /api/employees/{id} ─────────────────────

    @Nested
    @DisplayName("DELETE /api/employees/{id}")
    class DeleteEmployee {

        @Test
        @DisplayName("should return 200 when employee deleted successfully")
        void deleteEmployee_existingId_returns200() throws Exception {
            willDoNothing().given(employeeService).deleteEmployee(EMP_ID);

            mockMvc.perform(delete(BASE_URL + "/{id}", EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("Employee deleted successfully"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            verify(employeeService).deleteEmployee(EMP_ID);
        }

        @Test
        @DisplayName("should throw when deleting non-existing employee")
        void deleteEmployee_nonExistingId_throwsException() {
            willThrow(new RuntimeException("Employee not found with id: 999"))
                    .given(employeeService).deleteEmployee(999L);

            assertThatThrownBy(() ->
                    mockMvc.perform(delete(BASE_URL + "/{id}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Employee not found with id: 999");
        }
    }

    // ───────────────────── GET /api/employees/department/{departmentId} ─────────────────────

    @Nested
    @DisplayName("GET /api/employees/department/{departmentId}")
    class GetEmployeesByDepartment {

        @Test
        @DisplayName("should return 200 and employees for given department")
        void getByDepartment_existingDept_returnsList() throws Exception {
            EmployeeDto emp1 = buildEmployeeDto(1L, "John", "Doe", "john@ems.com");
            EmployeeDto emp2 = buildEmployeeDto(2L, "Jane", "Smith", "jane@ems.com");

            given(employeeService.getEmployeesByDepartment(DEPT_ID)).willReturn(List.of(emp1, emp2));

            mockMvc.perform(get(BASE_URL + "/department/{departmentId}", DEPT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)));

            verify(employeeService).getEmployeesByDepartment(DEPT_ID);
        }

        @Test
        @DisplayName("should return 200 and empty list when department has no employees")
        void getByDepartment_noEmployees_returnsEmptyList() throws Exception {
            given(employeeService.getEmployeesByDepartment(DEPT_ID)).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL + "/department/{departmentId}", DEPT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("should throw when department does not exist")
        void getByDepartment_nonExistingDept_throwsException() {
            given(employeeService.getEmployeesByDepartment(999L))
                    .willThrow(new RuntimeException("Department not found with id: 999"));

            assertThatThrownBy(() ->
                    mockMvc.perform(get(BASE_URL + "/department/{departmentId}", 999L)))
                    .isInstanceOf(ServletException.class)
                    .hasCauseInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Department not found with id: 999");
        }
    }

    // ───────────────────── GET /api/employees/search ─────────────────────

    @Nested
    @DisplayName("GET /api/employees/search")
    class SearchEmployees {

        @Test
        @DisplayName("should return 200 and matching employees for valid query")
        void searchEmployees_validQuery_returnsList() throws Exception {
            EmployeeDto emp = buildDefaultEmployee();

            given(employeeService.searchEmployees("John")).willReturn(List.of(emp));

            mockMvc.perform(get(BASE_URL + "/search")
                            .param("query", "John"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].firstName").value("John"));

            verify(employeeService).searchEmployees("John");
        }

        @Test
        @DisplayName("should return 200 and empty list when no matches found")
        void searchEmployees_noMatches_returnsEmptyList() throws Exception {
            given(employeeService.searchEmployees("ZZZZZ")).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE_URL + "/search")
                            .param("query", "ZZZZZ"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("should return 400 when query parameter is missing")
        void searchEmployees_missingQuery_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/search"))
                    .andExpect(status().isBadRequest());
        }
    }
}
