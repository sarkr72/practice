package com.ems.ems.dtos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeDto implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    @Size(max = 255)
    private String email;

    @Size(max = 32)
    private String phone;

    @Size(max = 100)
    private String jobTitle;

    // BigDecimal for money; never Double.
    // inclusive = false so zero/negative salaries are rejected.
    @DecimalMin(value = "0.0", inclusive = false, message = "Salary must be positive")
    private BigDecimal salary;

    private LocalDate hireDate;

    private Boolean active;

    private Long departmentId;

    private String departmentName;
}
