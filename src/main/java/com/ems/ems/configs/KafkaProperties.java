package com.ems.ems.configs;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All knobs ops can turn without redeploying code.
 * Bound from `ems.kafka.*` in application.yml. Use @EnableConfigurationProperties(KafkaProperties.class)
 * on KafkaConfig (already done below) or annotate this class with @ConfigurationPropertiesScan.
 */
@ConfigurationProperties(prefix = "ems.kafka")
public record KafkaProperties(
        boolean enabled,
        Topics topics,
        Producer producer,
        Consumer consumer,
        Retry retry
) {

    public record Topics(
            int partitions,
            short replicationFactor,
            Duration retention,
            Duration dltRetention,
            int minInsyncReplicas
    ) {}

    public record Producer(
            String clientId,
            String compressionType,   // zstd, lz4, snappy
            int lingerMs,
            int batchSize,
            Duration deliveryTimeout,
            Duration requestTimeout
    ) {}

    public record Consumer(
            String clientId,
            String groupId,
            int concurrency,
            int maxPollRecords,
            Duration maxPollInterval,
            Duration sessionTimeout,
            Duration heartbeatInterval,
            String autoOffsetReset,
            String isolationLevel     // read_committed | read_uncommitted
    ) {}

    public record Retry(
            int maxAttempts,
            Duration initialInterval,
            double multiplier,
            Duration maxInterval
    ) {}
}
