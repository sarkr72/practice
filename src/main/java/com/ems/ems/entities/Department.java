package com.ems.ems.entities;

import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

/**
 * A Department groups Employees but does NOT own their lifecycle.
 *
 * <p>Deliberate choices:
 * <ul>
 *   <li>No {@code cascade = ALL} and no {@code orphanRemoval} — Employees have
 *       independent lifecycles (reassignment, inter-department moves, etc.).
 *       Deletion policy is enforced explicitly in the service layer.</li>
 *   <li>{@link Set} rather than {@link java.util.List} — an employee can only
 *       belong to a department once, and {@code Set} avoids Hibernate's
 *       {@code MultipleBagFetchException} should another collection be added
 *       later.</li>
 *   <li>Bidirectional helper methods keep both sides of the association in
 *       memory-consistent; the service layer should use these rather than
 *       mutating the collection directly.</li>
 * </ul>
 */
@Entity
@Table(name = "departments")
public class Department extends BaseEntity {

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @OneToMany(mappedBy = "department", fetch = FetchType.LAZY)
    private Set<Employee> employees = new HashSet<>();

    public Department() {
    }

    public Department(String name, String description) {
        this.name = name;
        this.description = description;
    }

    // ───────────────────── bidirectional helpers ─────────────────────

    /**
     * Assigns the employee to this department and keeps both sides of the
     * association in sync. Use this rather than calling
     * {@code employee.setDepartment(...)} directly.
     */
    public void addEmployee(Employee employee) {
        if (employee == null) return;
        employees.add(employee);
        employee.setDepartment(this);
    }

    /**
     * Detaches the employee from this department. Does NOT delete the
     * employee — that is a service-layer concern with an explicit policy.
     */
    public void removeEmployee(Employee employee) {
        if (employee == null) return;
        employees.remove(employee);
        if (this.equals(employee.getDepartment())) {
            employee.setDepartment(null);
        }
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

    /** Returns an unmodifiable live view; use helper methods to mutate. */
    public Set<Employee> getEmployees() {
        return employees;
    }
}
