package com.ems.ems.configs;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Global CORS. Allowed origins are property-driven so prod can lock down
 * to specific domains while local dev permits localhost.
 *
 * Override via: ems.cors.allowed-origins=https://app.example.com,https://admin.example.com
 */
@Configuration
public class CorsConfig {

    @Value("${ems.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOriginsCsv;

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(Arrays.asList(allowedOriginsCsv.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Requested-With",
                "X-Request-Id", "X-Trace-Id"));
        config.setExposedHeaders(List.of("X-Request-Id", "X-Trace-Id", "Location"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/actuator/**", config);
        return new CorsFilter(source);
    }
}
