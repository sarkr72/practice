package com.ems.ems.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import com.ems.ems.dtos.DepartmentDto;
import com.ems.ems.entities.Department;
import com.ems.ems.events.DepartmentEvent;
import com.ems.ems.events.EventType;
import com.ems.ems.exceptions.DuplicateResourceException;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.kafka.DepartmentEventProducer;
import com.ems.ems.repositories.DepartmentRepository;
import com.ems.ems.serviceImpl.DepartmentServiceImpl;

@ExtendWith(MockitoExtension.class)
@DisplayName("DepartmentServiceImpl")
class DepartmentServiceImplTest {

    @Mock
    private DepartmentRepository departmentRepository;

    @Mock
    private ObjectProvider<DepartmentEventProducer> eventProducerProvider;

    @Mock
    private DepartmentEventProducer eventProducer;

    @Captor
    private ArgumentCaptor<DepartmentEvent> eventCaptor;

    @Captor
    private ArgumentCaptor<Department> entityCaptor;

    @InjectMocks
    private DepartmentServiceImpl departmentService;

    private DepartmentDto newRequest;
    private Department savedEntity;

    @BeforeEach
    void setUp() {
        newRequest = DepartmentDto.builder()
                .id(null)
                .name("Platform Engineering")
                .description("Owns infra + devex")
                .build();

        savedEntity = new Department();
        savedEntity.setId(42L);
        savedEntity.setName("Platform Engineering");
        savedEntity.setDescription("Owns infra + devex");

        // Make ObjectProvider.ifAvailable(consumer) actually invoke the consumer
        // with our producer mock. lenient() so negative-path tests that never
        // publish don't trip UnnecessaryStubbingException.
        lenient().doAnswer(inv -> {
            Consumer<DepartmentEventProducer> consumer = inv.getArgument(0);
            consumer.accept(eventProducer);
            return null;
        }).when(eventProducerProvider).ifAvailable(any());
    }

    // =====================================================================
    // create()
    // =====================================================================
    @Nested
    @DisplayName("create()")
    class Create {

        @Test
        @DisplayName("persists the department and publishes a CREATED event on happy path")
        void create_happyPath_persistsAndPublishesCreatedEvent() {
            given(departmentRepository.existsByNameIgnoreCase("Platform Engineering"))
                    .willReturn(false);
            given(departmentRepository.save(any(Department.class)))
                    .willReturn(savedEntity);

            DepartmentDto result = departmentService.createDepartment(newRequest);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getName()).isEqualTo("Platform Engineering");
            assertThat(result.getDescription()).isEqualTo("Owns infra + devex");

            then(departmentRepository).should().save(entityCaptor.capture());
            Department persisted = entityCaptor.getValue();
            assertThat(persisted.getName()).isEqualTo("Platform Engineering");
            assertThat(persisted.getDescription()).isEqualTo("Owns infra + devex");

            then(eventProducer).should().publish(eventCaptor.capture());
            DepartmentEvent published = eventCaptor.getValue();
            assertThat(published.eventType()).isEqualTo(EventType.CREATED);
            assertThat(published.departmentId()).isEqualTo(42L);
            assertThat(published.name()).isEqualTo("Platform Engineering");
        }

        @Test
        @DisplayName("throws DuplicateResourceException when name exists")
        void create_whenNameExists_throws() {
            given(departmentRepository.existsByNameIgnoreCase("Platform Engineering"))
                    .willReturn(true);

            assertThatThrownBy(() -> departmentService.createDepartment(newRequest))
                    .isInstanceOf(DuplicateResourceException.class)
                    .hasMessageContaining("Platform Engineering");

            then(departmentRepository).should(never()).save(any());
            verifyNoInteractions(eventProducer);
        }

        @Test
        @DisplayName("does not publish event if save fails")
        void create_whenSaveFails_noEvent() {
            given(departmentRepository.existsByNameIgnoreCase("Platform Engineering"))
                    .willReturn(false);
            given(departmentRepository.save(any(Department.class)))
                    .willThrow(new RuntimeException("DB down"));

            assertThatThrownBy(() -> departmentService.createDepartment(newRequest))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessage("DB down");

            verifyNoInteractions(eventProducer);
        }
    }

    // =====================================================================
    // findById()
    // =====================================================================
    @Nested
    @DisplayName("findById()")
    class FindById {

        @Test
        void findById_found() {
            given(departmentRepository.findById(42L))
                    .willReturn(Optional.of(savedEntity));

            DepartmentDto result = departmentService.getDepartmentById(42L);

            assertThat(result.getId()).isEqualTo(42L);
            assertThat(result.getName()).isEqualTo("Platform Engineering");
            assertThat(result.getDescription()).isEqualTo("Owns infra + devex");

            verifyNoInteractions(eventProducer);
        }

        @Test
        void findById_notFound() {
            given(departmentRepository.findById(999L))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> departmentService.getDepartmentById(999L))
                    .isInstanceOf(ResourceNotFoundException.class);

            verifyNoInteractions(eventProducer);
        }
    }

    // =====================================================================
    // findAll()
    // =====================================================================
    @Nested
    @DisplayName("findAll()")
    class FindAll {

        @Test
        void findAll_returnsList() {
            Department second = new Department();
            second.setId(43L);
            second.setName("Risk");
            second.setDescription("Quant + market risk");

            given(departmentRepository.findAll())
                    .willReturn(List.of(savedEntity, second));

            List<DepartmentDto> result = departmentService.getAllDepartments();

            assertThat(result)
                    .hasSize(2)
                    .extracting(DepartmentDto::getName)
                    .containsExactly("Platform Engineering", "Risk");
        }

        @Test
        void findAll_empty() {
            given(departmentRepository.findAll()).willReturn(List.of());

            List<DepartmentDto> result = departmentService.getAllDepartments();

            assertThat(result).isEmpty();
        }
    }

    // =====================================================================
    // update()
    // =====================================================================
    @Nested
    @DisplayName("update()")
    class Update {

        @Test
        void update_success() {
            DepartmentDto patch = DepartmentDto.builder()
                    .name("Platform")
                    .description("Updated description")
                    .build();

            given(departmentRepository.findById(42L))
                    .willReturn(Optional.of(savedEntity));
            given(departmentRepository.save(any(Department.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            DepartmentDto result = departmentService.updateDepartment(42L, patch);

            then(departmentRepository).should().save(entityCaptor.capture());
            Department persisted = entityCaptor.getValue();
            assertThat(persisted.getName()).isEqualTo("Platform");
            assertThat(persisted.getDescription()).isEqualTo("Updated description");

            then(eventProducer).should().publish(eventCaptor.capture());
            assertThat(eventCaptor.getValue().eventType()).isEqualTo(EventType.UPDATED);

            assertThat(result.getName()).isEqualTo("Platform");
            assertThat(result.getDescription()).isEqualTo("Updated description");
        }

        @Test
        void update_notFound() {
            given(departmentRepository.findById(999L))
                    .willReturn(Optional.empty());

            DepartmentDto patch = DepartmentDto.builder()
                    .name("X")
                    .description("Y")
                    .build();

            assertThatThrownBy(() -> departmentService.updateDepartment(999L, patch))
                    .isInstanceOf(ResourceNotFoundException.class);

            then(departmentRepository).should(never()).save(any());
            verifyNoInteractions(eventProducer);
        }
    }

    // =====================================================================
    // delete()
    // =====================================================================
    @Nested
    @DisplayName("delete()")
    class Delete {

        @Test
        void delete_success() {
            given(departmentRepository.existsById(42L)).willReturn(true);

            departmentService.deleteDepartment(42L);

            then(departmentRepository).should().deleteById(42L);
            then(eventProducer).should().publish(eventCaptor.capture());

            DepartmentEvent published = eventCaptor.getValue();
            assertThat(published.eventType()).isEqualTo(EventType.DELETED);
            assertThat(published.departmentId()).isEqualTo(42L);
        }

        @Test
        void delete_notFound() {
            given(departmentRepository.existsById(999L)).willReturn(false);

            assertThatThrownBy(() -> departmentService.deleteDepartment(999L))
                    .isInstanceOf(ResourceNotFoundException.class);

            then(departmentRepository).should(never()).deleteById(any());
            verifyNoInteractions(eventProducer);
        }
    }
}