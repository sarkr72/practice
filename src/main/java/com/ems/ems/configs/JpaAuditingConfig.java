package com.ems.ems.configs;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Enables Spring Data JPA auditing and supplies the "who" for
 * @CreatedBy / @LastModifiedBy fields on BaseEntity.
 *
 * Once Spring Security is wired up, swap the AuditorAware body for
 * SecurityContextHolder.getContext().getAuthentication() lookup.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    public AuditorAware<String> auditorAware() {
        // Placeholder: every write is attributed to "system" until Security is added.
        // Do NOT throw here - auditing fires during tests too.
        return () -> Optional.of("system");
    }
}
