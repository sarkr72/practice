package com.ems.ems.entities;

import java.time.Instant;
import java.util.Objects;

import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

/**
 * Base class for every persistent entity in the EMS domain.
 *
 * <p>Provides:
 * <ul>
 *   <li>A surrogate {@code IDENTITY} primary key.</li>
 *   <li>An optimistic-locking {@link Version} column.</li>
 *   <li>Four audit columns populated by Spring Data JPA auditing:
 *       {@code createdAt}, {@code updatedAt}, {@code createdBy}, {@code updatedBy}.
 *       Requires {@code @EnableJpaAuditing} and an {@code AuditorAware<String>}
 *       bean (provided by {@code AuditConfig}).</li>
 *   <li>Proxy-safe {@link #equals(Object)} and {@link #hashCode()} based on
 *       the primary key, following Vlad Mihalcea's recommended pattern.</li>
 * </ul>
 *
 * <p>The id is intentionally not settable from application code — it is
 * assigned by the database on persist. Tests that need to fabricate an id
 * should use reflection or a dedicated test builder.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 100, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    // ───────────────────── accessors ─────────────────────

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    // ───────────────────── equals / hashCode ─────────────────────

    /**
     * Equality is based on the primary key. Two transient entities (id == null)
     * are equal only by reference. Hibernate proxies are unwrapped so a proxy
     * and its underlying entity compare equal.
     */
    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> thisClass = unwrapClass(this);
        Class<?> otherClass = unwrapClass(o);
        if (!thisClass.equals(otherClass)) return false;
        BaseEntity that = (BaseEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    /**
     * Returns a stable hash independent of id so an entity's hash does not
     * change when it is persisted and assigned an id. This is critical when
     * entities live in {@link java.util.HashSet} before and after persist.
     */
    @Override
    public final int hashCode() {
        return unwrapClass(this).hashCode();
    }

    private static Class<?> unwrapClass(Object o) {
        return (o instanceof HibernateProxy proxy)
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : o.getClass();
    }

    @Override
    public String toString() {
        // Deliberately omits lazy fields so logging never triggers a fetch.
        return getClass().getSimpleName() + "{id=" + id + ", version=" + version + "}";
    }
}
