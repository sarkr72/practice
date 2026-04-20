package com.ems.ems.entities;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Design choices worth noting:
 * <ul>
 *   <li>{@code salary} is {@link BigDecimal} — never {@code double} or
 *       {@code Double} for money. Precision (19, scale 2) supports values up
 *       to 10<sup>17</sup>, well beyond any realistic compensation.</li>
 *   <li>Explicit indexes on {@code department_id} and {@code last_name}
 *       improve lookup and search performance and document the access
 *       patterns to DBAs.</li>
 *   <li>The {@code projects} inverse-side collection has no public setter.
 *       Membership is managed via {@link Project#addEmployee(Employee)} and
 *       {@link Project#removeEmployee(Employee)}, which is the owning side.</li>
 * </ul>
 */
@Entity
@Table(
    name = "employees",
    indexes = {
        @Index(name = "idx_employee_department", columnList = "department_id"),
        @Index(name = "idx_employee_last_name",  columnList = "last_name")
    }
)
public class Employee extends BaseEntity {

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(name = "job_title", length = 100)
    private String jobTitle;

    /**
     * Monetary amount — stored as DECIMAL(19,2). Never switch this to a
     * floating-point type: IEEE-754 rounding produces cent-level drift that
     * violates financial reporting requirements.
     */
    @Column(precision = 19, scale = 2)
    private BigDecimal salary;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    @ManyToMany(mappedBy = "employees", fetch = FetchType.LAZY)
    private Set<Project> projects = new HashSet<>();

    public Employee() {
    }

    // ───────────────────── accessors ─────────────────────

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public BigDecimal getSalary() {
        return salary;
    }

    public void setSalary(BigDecimal salary) {
        this.salary = salary;
    }

    public LocalDate getHireDate() {
        return hireDate;
    }

    public void setHireDate(LocalDate hireDate) {
        this.hireDate = hireDate;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Department getDepartment() {
        return department;
    }

    public void setDepartment(Department department) {
        this.department = department;
    }

    /** Read-only; mutate via {@link Project#addEmployee(Employee)} on the owning side. */
    public Set<Project> getProjects() {
        return projects;
    }
}
