package com.ems.ems.serviceImpl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ems.ems.dtos.DepartmentDto;
import com.ems.ems.entities.Department;
import com.ems.ems.events.DepartmentEvent;
import com.ems.ems.exceptions.DuplicateResourceException;
import com.ems.ems.exceptions.ResourceNotFoundException;
import com.ems.ems.kafka.DepartmentEventProducer;
import com.ems.ems.repositories.DepartmentRepository;
import com.ems.ems.services.DepartmentService;

@Service
@Transactional(readOnly = true)
public class DepartmentServiceImpl implements DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentServiceImpl.class);
    private static final String CACHE_NAME = "departments";

    private final DepartmentRepository departmentRepository;
    private final ObjectProvider<DepartmentEventProducer> eventProducerProvider;

    public DepartmentServiceImpl(DepartmentRepository departmentRepository,
                                 ObjectProvider<DepartmentEventProducer> eventProducerProvider) {
        this.departmentRepository = departmentRepository;
        this.eventProducerProvider = eventProducerProvider;
    }

    @Override
    @Cacheable(value = CACHE_NAME, key = "#id")
    public DepartmentDto getDepartmentById(Long id) {
        log.debug("Cache miss for department id={}", id);
        Department entity = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
        return toDto(entity);
    }

    @Override
    public List<DepartmentDto> getAllDepartments() {
        return departmentRepository.findAll().stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    @CacheEvict(value = CACHE_NAME, allEntries = true)
    public DepartmentDto createDepartment(DepartmentDto dto) {
        if (departmentRepository.existsByNameIgnoreCase(dto.getName())) {
            throw new DuplicateResourceException("Department", "name", dto.getName());
        }

        Department entity = new Department();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        Department saved = departmentRepository.save(entity);

        publish(DepartmentEvent.created(saved.getId(), saved.getName(), saved.getDescription(),
                saved.getVersion()));

        return toDto(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = CACHE_NAME, key = "#id")
    public DepartmentDto updateDepartment(Long id, DepartmentDto dto) {
        Department entity = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));

        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        Department saved = departmentRepository.save(entity);

        publish(DepartmentEvent.updated(saved.getId(), saved.getName(), saved.getDescription(),
                saved.getVersion()));

        return toDto(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = CACHE_NAME, key = "#id")
    public void deleteDepartment(Long id) {
        if (!departmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Department", "id", id);
        }
        departmentRepository.deleteById(id);
        publish(DepartmentEvent.deleted(id));
    }

    // ---------- helpers ----------

    private void publish(DepartmentEvent event) {
        eventProducerProvider.ifAvailable(p -> p.publish(event));
    }

    private DepartmentDto toDto(Department entity) {
        return DepartmentDto.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .employeeCount(entity.getEmployees() == null ? 0 : entity.getEmployees().size())
                .build();
    }
}
