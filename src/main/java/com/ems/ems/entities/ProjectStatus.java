package com.ems.ems.entities;

/**
 * Lifecycle states for a {@link Project}.
 *
 * <p>Stored in the database as a {@code VARCHAR} via
 * {@code @Enumerated(EnumType.STRING)} — never {@code ORDINAL}, which would
 * silently re-number values when the enum is reordered.
 */
public enum ProjectStatus {
    PLANNING,
    ACTIVE,
    ON_HOLD,
    COMPLETED,
    CANCELLED
}
