//package com.ems.ems.configs;
//
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Configuration;
//import org.springframework.security.config.annotation.web.builders.HttpSecurity;
//import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
//import org.springframework.security.config.http.SessionCreationPolicy;
//import org.springframework.security.web.SecurityFilterChain;
//
///**
// * Baseline Spring Security setup - permissive on purpose for now.
// *
// * Current state (EARLY PROJECT):
// *   - Actuator health/info/prometheus : open
// *   - Swagger UI + /v3/api-docs       : open
// *   - /api/**                         : open (no auth yet)
// *   - Everything else                 : authenticated
// *   - CSRF disabled (stateless REST)
// *   - Sessions STATELESS
// *
// * PRODUCTION TODO (track as separate ticket):
// *   - Add OAuth2 resource server (JWT) with .oauth2ResourceServer(oauth2 -> oauth2.jwt())
// *   - Restrict /api/** to authenticated + @PreAuthorize on sensitive methods
// *   - Pull user info into the AuditorAware bean in JpaAuditingConfig
// *
// * REQUIRES in pom.xml:
// *   <dependency>
// *     <groupId>org.springframework.boot</groupId>
// *     <artifactId>spring-boot-starter-security</artifactId>
// *   </dependency>
// */
//@Configuration
//@EnableWebSecurity
//public class SecurityConfig {
//
//    @Bean
//    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
//        http
//                .csrf(csrf -> csrf.disable())
//                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
//                .authorizeHttpRequests(auth -> auth
//                        .requestMatchers(
//                                "/actuator/health/**",
//                                "/actuator/info",
//                                "/actuator/prometheus",
//                                "/v3/api-docs/**",
//                                "/swagger-ui/**",
//                                "/swagger-ui.html"
//                        ).permitAll()
//                        // Temporarily open until auth is wired up:
//                        .requestMatchers("/api/**").permitAll()
//                        .requestMatchers("/test/**").permitAll()
//                        .anyRequest().authenticated())
//                .httpBasic(basic -> {})     // dev fallback; remove once JWT is in place
//                .formLogin(form -> form.disable());
//
//        return http.build();
//    }
//}
package com.ems.ems.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for EMS application.
 *
 * CURRENT MODE (Stateless REST API):
 * - No sessions (STATELESS)
 * - No cookies used for authentication
 * - CSRF disabled (SAFE in this context)
 *
 * WHY CSRF IS DISABLED:
 * CSRF protection is only required when using cookie-based authentication.
 * This API is stateless and will use JWT (Authorization header),
 * so CSRF is not applicable.
 *
 * FUTURE (PRODUCTION):
 * - Enable JWT:
 *     .oauth2ResourceServer(oauth2 -> oauth2.jwt())
 * - Secure endpoints (/api/**)
 * - Add role-based access control (@PreAuthorize)
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                // ✅ Safe because: no sessions, no cookies (stateless API)
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers(
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()

                        // TEMP: open APIs (lock this down when auth is added)
                        .requestMatchers("/api/**").permitAll()
                        .requestMatchers("/test/**").permitAll()

                        // Everything else requires auth
                        .anyRequest().authenticated()
                )

                // Dev fallback (remove in production)
                .httpBasic(basic -> {})

                // Disable form login (API only)
                .formLogin(form -> form.disable());

        return http.build();
    }
}