package com.ems.ems.configs;

public final class CacheNames {

    private CacheNames() {
        // utility class — not instantiable
    }

    // ── Single-item caches (keyed by id) ──
    public static final String DEPARTMENT = "departments";
    public static final String EMPLOYEE   = "employees";
    public static final String PROJECT    = "projects";

    // ── List caches (findAll / findByX) ──
    public static final String DEPARTMENT_LIST = "departments-list";
    public static final String EMPLOYEE_LIST   = "employees-list";
    public static final String PROJECT_LIST    = "projects-list";

    // ── Search caches (short TTL, high churn) ──
    public static final String EMPLOYEE_SEARCH = "employees-search";
}
