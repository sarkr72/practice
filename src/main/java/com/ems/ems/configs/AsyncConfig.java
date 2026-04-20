package com.ems.ems.configs;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Value("${ems.async.core-pool-size:5}")
    private int corePoolSize;

    @Value("${ems.async.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${ems.async.queue-capacity:25}")
    private int queueCapacity;

    @Value("${ems.async.await-termination-seconds:30}")
    private int awaitTerminationSeconds;

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ems-async-");

        // Apply backpressure to the caller when the queue is full rather than
        // dropping work with a RejectedExecutionException.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Graceful shutdown: let in-flight tasks complete, but cap the wait.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);

        // Propagate SLF4J MDC (correlation IDs, user IDs) to worker threads.
        executor.setTaskDecorator(new MdcTaskDecorator());

        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                log.error("Uncaught exception in async method {}.{}",
                        method.getDeclaringClass().getSimpleName(),
                        method.getName(),
                        ex);
    }
}