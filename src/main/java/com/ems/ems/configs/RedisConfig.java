package com.ems.ems.configs;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheKeyPrefix;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.ems.ems.dtos.DepartmentDto;
import com.ems.ems.dtos.EmployeeDto;
import com.ems.ems.dtos.ProjectDto;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
@ConditionalOnProperty(name = "spring.data.redis.host")
public class RedisConfig {

    @Value("${ems.cache.key-prefix:ems:}")
    private String keyPrefix;

    @Value("${ems.cache.default-ttl-minutes:30}")
    private long defaultTtlMinutes;

    // ───────────────────── serializers ─────────────────────

    private static final StringRedisSerializer STRING_SERIALIZER = new StringRedisSerializer();

    private ObjectMapper redisObjectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private <T> Jackson2JsonRedisSerializer<T> jsonSerializer(Class<T> type) {
        return new Jackson2JsonRedisSerializer<>(redisObjectMapper(), type);
    }

    private RedisSerializer<Object> listSerializer(Class<?> elementType) {
        ObjectMapper mapper = redisObjectMapper();
        JavaType listType = mapper.getTypeFactory().constructCollectionType(List.class, elementType);
        return new Jackson2JsonRedisSerializer<>(mapper, listType);
    }

    // ───────────────────── RedisTemplate ─────────────────────

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        Jackson2JsonRedisSerializer<Object> valueSerializer =
                new Jackson2JsonRedisSerializer<>(redisObjectMapper(), Object.class);

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(STRING_SERIALIZER);
        template.setValueSerializer(valueSerializer);
        template.setHashKeySerializer(STRING_SERIALIZER);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    // ───────────────────── CacheManager ─────────────────────

    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {

        RedisCacheConfiguration defaults = buildCacheConfig(
                Duration.ofMinutes(defaultTtlMinutes),
                new Jackson2JsonRedisSerializer<>(redisObjectMapper(), Object.class));

        Map<String, RedisCacheConfiguration> perCacheConfigs = new HashMap<>();

        // Single-item caches — for findById-style methods.
        perCacheConfigs.put(CacheNames.DEPARTMENT,
                buildCacheConfig(Duration.ofHours(1),    jsonSerializer(DepartmentDto.class)));
        perCacheConfigs.put(CacheNames.EMPLOYEE,
                buildCacheConfig(Duration.ofMinutes(15), jsonSerializer(EmployeeDto.class)));
        perCacheConfigs.put(CacheNames.PROJECT,
                buildCacheConfig(Duration.ofMinutes(15), jsonSerializer(ProjectDto.class)));

        // List caches — for findAll / search / findByX-style methods.
        perCacheConfigs.put(CacheNames.DEPARTMENT_LIST,
                buildCacheConfig(Duration.ofHours(1),    listSerializer(DepartmentDto.class)));
        perCacheConfigs.put(CacheNames.EMPLOYEE_LIST,
                buildCacheConfig(Duration.ofMinutes(15), listSerializer(EmployeeDto.class)));
        perCacheConfigs.put(CacheNames.PROJECT_LIST,
                buildCacheConfig(Duration.ofMinutes(15), listSerializer(ProjectDto.class)));
        perCacheConfigs.put(CacheNames.EMPLOYEE_SEARCH,
                buildCacheConfig(Duration.ofMinutes(2),  listSerializer(EmployeeDto.class)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCacheConfigs)
                .transactionAware()
                .build();
    }

    private RedisCacheConfiguration buildCacheConfig(Duration ttl, RedisSerializer<?> valueSerializer) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .computePrefixWith(CacheKeyPrefix.prefixed(keyPrefix))
                .serializeKeysWith(SerializationPair.fromSerializer(STRING_SERIALIZER))
                .serializeValuesWith(SerializationPair.fromSerializer(valueSerializer))
                .disableCachingNullValues();
    }
}