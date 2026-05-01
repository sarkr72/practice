package com.ems.ems.configs;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Owns the @EnableCaching annotation. The actual CacheManager bean is in
 * RedisConfig because the configuration is Redis-specific.
 *
 * Kept separate so slice tests can opt in/out of caching cleanly.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
