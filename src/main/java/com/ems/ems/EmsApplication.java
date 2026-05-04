package com.ems.ems;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import jakarta.annotation.PostConstruct;

@SpringBootApplication
@EnableCaching
@EnableAsync
@EnableScheduling
@EnableTransactionManagement
@EnableRetry
public class EmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmsApplication.class, args);
    }

    @PostConstruct
    void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }
}
