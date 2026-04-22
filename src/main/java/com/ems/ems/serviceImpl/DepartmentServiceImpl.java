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
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + id));
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
            throw new DuplicateResourceException("Department name already exists: " + dto.getName());
        }

        Department entity = new Department();
        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        Department saved = departmentRepository.save(entity);

        publish(DepartmentEvent.created(saved.getId(), saved.getName(), saved.getDescription(),
                /* version */ 0L));

        return toDto(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = CACHE_NAME, key = "#id")
    public DepartmentDto updateDepartment(Long id, DepartmentDto dto) {
        Department entity = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department not found: " + id));

        entity.setName(dto.getName());
        entity.setDescription(dto.getDescription());
        Department saved = departmentRepository.save(entity);

        publish(DepartmentEvent.updated(saved.getId(), saved.getName(), saved.getDescription(),
                /* version */ 0L));

        return toDto(saved);
    }

    @Override
    @Transactional
    @CacheEvict(value = CACHE_NAME, key = "#id")
    public void deleteDepartment(Long id) {
        if (!departmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Department not found: " + id);
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
                .employeeCount(entity.getEmployees().size())
                .build();
    }
}




//package com.ems.ems.serviceImpl;
//
//import java.util.List;
//import java.util.stream.Collectors;
//
//import org.springframework.cache.annotation.CacheEvict;
//import org.springframework.cache.annotation.CachePut;
//import org.springframework.cache.annotation.Cacheable;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import com.ems.ems.dtos.DepartmentDto;
//import com.ems.ems.entities.Department;
//import com.ems.ems.exceptions.DuplicateResourceException;
//import com.ems.ems.exceptions.ResourceNotFoundException;
//import com.ems.ems.repositories.DepartmentRepository;
//import com.ems.ems.services.DepartmentService;
//
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class DepartmentServiceImpl implements DepartmentService {
//
//    private final DepartmentRepository departmentRepository;
//
//    @Override
//    @Transactional
//    @CacheEvict(value = "departments", allEntries = true)
//    public DepartmentDto createDepartment(DepartmentDto dto) {
//        if (departmentRepository.existsByName(dto.getName())) {
//            throw new DuplicateResourceException("Department", "name", dto.getName());
//        }
//
//        Department department = new Department();
//        department.setName(dto.getName());
//        department.setDescription(dto.getDescription());
//
//        Department saved = departmentRepository.save(department);
//        log.info("Created department with id: {}", saved.getId());
//        return mapToDto(saved);
//    }
//
//    @Override
//    @Cacheable(value = "department", key = "#id")
//    public DepartmentDto getDepartmentById(Long id) {
//        log.debug("Fetching department with id: {}", id);
//        Department department = departmentRepository.findById(id)
//                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
//        return mapToDto(department);
//    }
//
//    @Override
//    @Cacheable(value = "departments")
//    public List<DepartmentDto> getAllDepartments() {
//        log.debug("Fetching all departments");
//        return departmentRepository.findAll()
//                .stream()
//                .map(this::mapToDto)
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    @Transactional
//    @CachePut(value = "department", key = "#id")
//    @CacheEvict(value = "departments", allEntries = true)
//    public DepartmentDto updateDepartment(Long id, DepartmentDto dto) {
//        Department department = departmentRepository.findById(id)
//                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
//
//        if (!department.getName().equals(dto.getName())
//                && departmentRepository.existsByName(dto.getName())) {
//            throw new DuplicateResourceException("Department", "name", dto.getName());
//        }
//
//        department.setName(dto.getName());
//        department.setDescription(dto.getDescription());
//
//        Department updated = departmentRepository.save(department);
//        log.info("Updated department with id: {}", id);
//        return mapToDto(updated);
//    }
//
//    @Override
//    @Transactional
//    @CacheEvict(value = {"department", "departments"}, allEntries = true)
//    public void deleteDepartment(Long id) {
//        Department department = departmentRepository.findById(id)
//                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
//        departmentRepository.delete(department);
//        log.info("Deleted department with id: {}", id);
//    }
//
//    private DepartmentDto mapToDto(Department d) {
//        return DepartmentDto.builder()
//                .id(d.getId())
//                .name(d.getName())
//                .description(d.getDescription())
//                .employeeCount(d.getEmployees() != null ? d.getEmployees().size() : 0)
//                .build();
//    }
//}
