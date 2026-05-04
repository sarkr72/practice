package com.ems.ems.configs;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declarative topic provisioning. KafkaAdmin (auto-configured by Spring Boot)
 * picks these up at startup and creates them if absent.
 *
 * In real prod, topics are usually created by Terraform/Pulumi against the
 * managed cluster. This is the local-dev / Kind equivalent.
 */
@Configuration
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaTopicsConfig {

    public static final String DEPARTMENT_EVENTS_TOPIC = "ems.department.events.v1";
    public static final String DEPARTMENT_EVENTS_DLT   = "ems.department.events.v1.DLT";

    private final KafkaProperties props;

    public KafkaTopicsConfig(KafkaProperties props) {
        this.props = props;
    }

    @Bean
    public NewTopic departmentEventsTopic() {
        return TopicBuilder.name(DEPARTMENT_EVENTS_TOPIC)
                .partitions(props.topics().partitions())
                .replicas(props.topics().replicationFactor())
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(props.topics().retention().toMillis()))
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                        String.valueOf(props.topics().minInsyncReplicas()))
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.COMPRESSION_TYPE_CONFIG, "producer")
                .build();
    }

    @Bean
    public NewTopic departmentEventsDlt() {
        // DLT gets longer retention — we want time to investigate before messages age out.
        return TopicBuilder.name(DEPARTMENT_EVENTS_DLT)
                .partitions(props.topics().partitions())
                .replicas(props.topics().replicationFactor())
                .config(TopicConfig.RETENTION_MS_CONFIG,
                        String.valueOf(props.topics().dltRetention().toMillis()))
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG,
                        String.valueOf(props.topics().minInsyncReplicas()))
                .build();
    }
}
