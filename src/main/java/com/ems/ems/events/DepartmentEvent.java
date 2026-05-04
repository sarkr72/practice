package com.ems.ems.events;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record DepartmentEvent(
        @JsonProperty("eventId") UUID eventId,
        @JsonProperty("eventType") EventType eventType,
        @JsonProperty("occurredAt") Instant occurredAt,
        @JsonProperty("departmentId") Long departmentId,
        @JsonProperty("name") String name,
        @JsonProperty("description") String description,
        @JsonProperty("version") Long version
) implements Serializable {

    @JsonCreator
    public DepartmentEvent {
        // compact canonical ctor — Jackson will call this during deserialization
    }

    public static DepartmentEvent created(Long id, String name, String description, Long version) {
        return new DepartmentEvent(UUID.randomUUID(), EventType.CREATED, Instant.now(),
                id, name, description, version);
    }

    public static DepartmentEvent updated(Long id, String name, String description, Long version) {
        return new DepartmentEvent(UUID.randomUUID(), EventType.UPDATED, Instant.now(),
                id, name, description, version);
    }

    public static DepartmentEvent deleted(Long id) {
        return new DepartmentEvent(UUID.randomUUID(), EventType.DELETED, Instant.now(),
                id, null, null, null);
    }
}
