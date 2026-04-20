package com.ems.ems.repositories;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ems.ems.entities.Project;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    @Query("""
            SELECT DISTINCT p FROM Project p
            LEFT JOIN FETCH p.employees
            WHERE p.id = :id
            """)
    Optional<Project> findByIdWithEmployees(@Param("id") Long id);

    @Query("""
            SELECT DISTINCT p FROM Project p
            LEFT JOIN FETCH p.employees
            """)
    List<Project> findAllWithEmployees();

    @Query("""
            SELECT DISTINCT p FROM Project p
            JOIN p.employees e
            WHERE e.id = :employeeId
            """)
    List<Project> findByEmployeeId(@Param("employeeId") Long employeeId);
}
