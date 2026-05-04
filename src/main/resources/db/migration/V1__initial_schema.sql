-- V1__initial_schema.sql
-- Initial EMS schema. Flyway runs this on first startup against an empty database.
-- All subsequent schema changes go in V2__*.sql, V3__*.sql, etc. NEVER edit this file.

-- -------------------------------------------------------------------------
-- departments
-- -------------------------------------------------------------------------
CREATE TABLE departments (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    name         VARCHAR(100)  NOT NULL,
    description  VARCHAR(500),
    version      BIGINT        NOT NULL DEFAULT 0,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    CONSTRAINT pk_departments PRIMARY KEY (id),
    CONSTRAINT uk_departments_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -------------------------------------------------------------------------
-- employees
-- salary is DECIMAL(19,2) - BigDecimal on the Java side, never Double.
-- -------------------------------------------------------------------------
CREATE TABLE employees (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    first_name     VARCHAR(100)   NOT NULL,
    last_name      VARCHAR(100)   NOT NULL,
    email          VARCHAR(255)   NOT NULL,
    phone          VARCHAR(32),
    job_title      VARCHAR(100),
    salary         DECIMAL(19, 2),
    hire_date      DATE,
    active         BOOLEAN        NOT NULL DEFAULT TRUE,
    department_id  BIGINT,
    version        BIGINT         NOT NULL DEFAULT 0,
    created_at     DATETIME(6)    NOT NULL,
    updated_at     DATETIME(6)    NOT NULL,
    created_by     VARCHAR(64),
    updated_by     VARCHAR(64),
    CONSTRAINT pk_employees PRIMARY KEY (id),
    CONSTRAINT uk_employees_email UNIQUE (email),
    CONSTRAINT fk_employees_department
        FOREIGN KEY (department_id) REFERENCES departments (id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_employees_department_id ON employees (department_id);
CREATE INDEX idx_employees_last_name     ON employees (last_name);
CREATE INDEX idx_employees_active        ON employees (active);

-- -------------------------------------------------------------------------
-- projects
-- -------------------------------------------------------------------------
CREATE TABLE projects (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    name         VARCHAR(150)  NOT NULL,
    description  VARCHAR(1000),
    start_date   DATE,
    end_date     DATE,
    status       VARCHAR(32),
    version      BIGINT        NOT NULL DEFAULT 0,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL,
    created_by   VARCHAR(64),
    updated_by   VARCHAR(64),
    CONSTRAINT pk_projects PRIMARY KEY (id),
    CONSTRAINT uk_projects_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_projects_status ON projects (status);

-- -------------------------------------------------------------------------
-- employee_projects (M:N join)
-- -------------------------------------------------------------------------
CREATE TABLE employee_projects (
    project_id   BIGINT NOT NULL,
    employee_id  BIGINT NOT NULL,
    CONSTRAINT pk_employee_projects PRIMARY KEY (project_id, employee_id),
    CONSTRAINT fk_ep_project
        FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_ep_employee
        FOREIGN KEY (employee_id) REFERENCES employees (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_ep_employee_id ON employee_projects (employee_id);
