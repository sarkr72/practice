package com.ems.ems.configs;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "ems.kafka.enabled", havingValue = "true", matchIfMissing = false)
public class KafkaTopicConfig {

    public static final String DEPARTMENT_EVENTS_TOPIC = "ems.department.events";
    public static final String DEPARTMENT_EVENTS_DLT = "ems.department.events.DLT";

    @Bean
    public NewTopic departmentEventsTopic() {
        return TopicBuilder.name(DEPARTMENT_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1) // bump to 3 in prod; MSK module should set min.insync.replicas=2
                .config("retention.ms", String.valueOf(7L * 24 * 60 * 60 * 1000)) // 7 days
                .config("cleanup.policy", "delete")
                .build();
    }

    @Bean
    public NewTopic departmentEventsDltTopic() {
        return TopicBuilder.name(DEPARTMENT_EVENTS_DLT)
                .partitions(3)
                .replicas(1)
                .config("retention.ms", String.valueOf(14L * 24 * 60 * 60 * 1000)) // 14 days
                .build();
    }
}
