package com.ems.ems.controllers;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.ems.ems.exceptions.GlobalExceptionHandler;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.services.EmployeeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmployeeController")
class EmployeeControllerTest {

    private static final String BASE_URL = "/api/v1/employees";
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
        mockMvc = MockMvcBuilders.standaloneSetup(employeeController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private EmployeeDto buildEmployeeDto(Long id, String firstName, String lastName, String email) {
        return EmployeeDto.builder()
                .id(id)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .phone("555-0100")
                .jobTitle("Software Engineer")
                .salary(new BigDecimal("95000.00"))
                .hireDate(LocalDate.of(2024, 3, 15))
                .active(true)
                .departmentId(DEPT_ID)
                .departmentName("Engineering")
                .build();
    }

    private EmployeeDto buildDefaultEmployee() {
        return buildEmployeeDto(EMP_ID, "John", "Doe", "john.doe@ems.com");
    }

    private EmployeeDto buildCreateRequest() {
        return EmployeeDto.builder()
                .firstName("John")
                .lastName("Doe")
                .email("john.doe@ems.com")
                .phone("555-0100")
                .jobTitle("Software Engineer")
                .salary(new BigDecimal("95000.00"))
                .hireDate(LocalDate.of(2024, 3, 15))
                .departmentId(DEPT_ID)
                .build();
    }

    @Nested
    @DisplayName("POST " + BASE_URL)
    class CreateEmployee {

        @Test
        void valid_returns201() throws Exception {
            given(employeeService.createEmployee(any(EmployeeDto.class)))
                    .willReturn(buildDefaultEmployee());

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCreateRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(EMP_ID))
                    .andExpect(jsonPath("$.data.firstName").value("John"))
                    .andExpect(jsonPath("$.data.email").value("john.doe@ems.com"))
                    .andExpect(jsonPath("$.data.salary").value(95000.00));
        }

        @Test
        void blankFirstName_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setFirstName("");
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
            verify(employeeService, never()).createEmployee(any());
        }

        @Test
        void invalidEmail_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setEmail("not-an-email");
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void negativeSalary_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setSalary(new BigDecimal("-50000.00"));
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void zeroSalary_returns400() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setSalary(BigDecimal.ZERO);
            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void nullSalaryAllowed_returns201() throws Exception {
            EmployeeDto request = buildCreateRequest();
            request.setSalary(null);
            EmployeeDto response = buildDefaultEmployee();
            response.setSalary(null);
            given(employeeService.createEmployee(any(EmployeeDto.class))).willReturn(response);

            mockMvc.perform(post(BASE_URL)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/{id}")
    class GetEmployeeById {

        @Test
        void found_returns200() throws Exception {
            given(employeeService.getEmployeeById(EMP_ID)).willReturn(buildDefaultEmployee());

            mockMvc.perform(get(BASE_URL + "/{id}", EMP_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.departmentName").value("Engineering"));
        }

        @Test
        void notFound_returns404() throws Exception {
            given(employeeService.getEmployeeById(999L))
                    .willThrow(new ResourceNotFoundException("Employee", "id", 999L));

            mockMvc.perform(get(BASE_URL + "/{id}", 999L))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL)
    class GetAllEmployees {

        @Test
        void listsEmployees() throws Exception {
            given(employeeService.getAllEmployees()).willReturn(List.of(
                    buildEmployeeDto(1L, "John", "Doe", "j@ems.com"),
                    buildEmployeeDto(2L, "Jane", "Smith", "js@ems.com")));

            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(2)));
        }

        @Test
        void emptyListOk() throws Exception {
            given(employeeService.getAllEmployees()).willReturn(Collections.emptyList());
            mockMvc.perform(get(BASE_URL))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("PUT " + BASE_URL + "/{id}")
    class UpdateEmployee {

        @Test
        void valid_returns200() throws Exception {
            EmployeeDto response = buildDefaultEmployee();
            response.setSalary(new BigDecimal("130000.00"));
            given(employeeService.updateEmployee(eq(EMP_ID), any(EmployeeDto.class)))
                    .willReturn(response);

            mockMvc.perform(put(BASE_URL + "/{id}", EMP_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCreateRequest())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.salary").value(130000.00));
        }

        @Test
        void notFound_returns404() throws Exception {
            given(employeeService.updateEmployee(eq(999L), any(EmployeeDto.class)))
                    .willThrow(new ResourceNotFoundException("Employee", "id", 999L));

            mockMvc.perform(put(BASE_URL + "/{id}", 999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(buildCreateRequest())))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE " + BASE_URL + "/{id}")
    class DeleteEmployee {

        @Test
        void ok_returns200() throws Exception {
            willDoNothing().given(employeeService).deleteEmployee(EMP_ID);
            mockMvc.perform(delete(BASE_URL + "/{id}", EMP_ID))
                    .andExpect(status().isOk());
        }

        @Test
        void notFound_returns404() throws Exception {
            willThrow(new ResourceNotFoundException("Employee", "id", 999L))
                    .given(employeeService).deleteEmployee(999L);

            mockMvc.perform(delete(BASE_URL + "/{id}", 999L))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("GET " + BASE_URL + "/search")
    class SearchEmployees {

        @Test
        void validQuery_returnsMatches() throws Exception {
            given(employeeService.searchEmployees("John")).willReturn(List.of(buildDefaultEmployee()));
            mockMvc.perform(get(BASE_URL + "/search").param("query", "John"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data", hasSize(1)));
        }

        @Test
        void missingQuery_returns400() throws Exception {
            mockMvc.perform(get(BASE_URL + "/search"))
                    .andExpect(status().isBadRequest());
        }
    }
}
