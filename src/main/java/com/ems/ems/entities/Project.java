package com.ems.ems.entities;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

/**
 * A Project is the owning side of the Project&lt;-&gt;Employee many-to-many
 * association — modifications to {@link #employees} are what drive inserts
 * and deletes in the {@code project_employees} join table.
 *
 * <p>Design choices:
 * <ul>
 *   <li>{@link ProjectStatus} enum stored as {@code VARCHAR} via
 *       {@code @Enumerated(EnumType.STRING)} — never {@code ORDINAL}.</li>
 *   <li>No cascade on the M:N association — neither deleting a project should
 *       delete its employees, nor vice-versa.</li>
 *   <li>Bidirectional helpers keep both in-memory sides consistent; for M:N
 *       only the owning side drives SQL, but keeping both aligned avoids
 *       stale reads within a single transaction.</li>
 * </ul>
 */
@Entity
@Table(
    name = "projects",
    indexes = {
        @Index(name = "idx_project_status", columnList = "status")
    }
)
public class Project extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ProjectStatus status;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "project_employees",
            joinColumns = @JoinColumn(name = "project_id"),
            inverseJoinColumns = @JoinColumn(name = "employee_id")
    )
    private Set<Employee> employees = new HashSet<>();

    public Project() {
    }

    // ───────────────────── bidirectional helpers ─────────────────────

    /** Adds the employee to this project and mirrors the link on the inverse side. */
    public boolean addEmployee(Employee employee) {
        if (employee == null) return false;
        boolean added = employees.add(employee);
        if (added) {
            employee.getProjects().add(this);
        }
        return added;
    }

    /** Removes the employee from this project and mirrors the removal. */
    public boolean removeEmployee(Employee employee) {
        if (employee == null) return false;
        boolean removed = employees.remove(employee);
        if (removed) {
            employee.getProjects().remove(this);
        }
        return removed;
    }

    // ───────────────────── accessors ─────────────────────

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
    }

    public Set<Employee> getEmployees() {
        return employees;
    }
}
