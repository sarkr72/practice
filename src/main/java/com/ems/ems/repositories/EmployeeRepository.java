package com.ems.ems.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ems.ems.entities.Employee;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {

    Optional<Employee> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    @Query("""
            SELECT e FROM Employee e
            LEFT JOIN FETCH e.department
            WHERE e.department.id = :departmentId
            """)
    List<Employee> findByDepartmentId(@Param("departmentId") Long departmentId);

    @Query("""
            SELECT e FROM Employee e
            LEFT JOIN FETCH e.department
            WHERE LOWER(e.firstName) LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(e.lastName)  LIKE LOWER(CONCAT('%', :query, '%'))
               OR LOWER(e.email)     LIKE LOWER(CONCAT('%', :query, '%'))
            """)
    List<Employee> search(@Param("query") String query);

    @Query("""
            SELECT e FROM Employee e
            LEFT JOIN FETCH e.department
            WHERE e.id = :id
            """)
    Optional<Employee> findByIdWithDepartment(@Param("id") Long id);
}
