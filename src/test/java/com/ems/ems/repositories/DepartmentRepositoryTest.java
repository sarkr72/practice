package com.ems.ems.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.ems.ems.entities.Department;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase // H2 via test classpath
@ActiveProfiles("test")
@DisplayName("DepartmentRepository")
class DepartmentRepositoryTest {

    @Autowired
    private DepartmentRepository departmentRepository;

    @Test
    @DisplayName("save() populates BaseEntity audit fields (version, createdAt, updatedAt)")
    void save_populatesBaseEntityFields() {
        Department dept = new Department();
        dept.setName("Platform");
        dept.setDescription("Owns infra + devex");

        Department saved = departmentRepository.saveAndFlush(dept);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("existsByNameIgnoreCase matches regardless of case")
    void existsByNameIgnoreCase_isCaseInsensitive() {
        Department dept = new Department();
        dept.setName("Engineering");
        dept.setDescription("desc");
        departmentRepository.saveAndFlush(dept);

        assertThat(departmentRepository.existsByNameIgnoreCase("engineering")).isTrue();
        assertThat(departmentRepository.existsByNameIgnoreCase("ENGINEERING")).isTrue();
        assertThat(departmentRepository.existsByNameIgnoreCase("EnGiNeErInG")).isTrue();
        assertThat(departmentRepository.existsByNameIgnoreCase("Finance")).isFalse();
    }

    @Test
    @DisplayName("findByName returns empty when no match")
    void findByName_noMatch_returnsEmpty() {
        Optional<Department> result = departmentRepository.findByName("does-not-exist");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("update() bumps @Version")
    void update_bumpsVersion() {
        Department dept = new Department();
        dept.setName("Risk");
        dept.setDescription("v1");
        Department saved = departmentRepository.saveAndFlush(dept);
        assertThat(saved.getVersion()).isEqualTo(0L);

        saved.setDescription("v2");
        Department updated = departmentRepository.saveAndFlush(saved);

        assertThat(updated.getVersion()).isEqualTo(1L);
    }
}
