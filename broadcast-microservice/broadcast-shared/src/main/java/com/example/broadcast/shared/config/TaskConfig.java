package com.example.broadcast.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Configuration
public class TaskConfig {

    /**
     * Customizes the thread pool for @Async methods.
     */
    @Bean
    public AsyncTaskExecutor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setThreadNamePrefix("async-task-");
        executor.initialize();
        return executor;
    }

    /**
     * Customizes the thread pool for @Scheduled methods.
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(10);
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public Scheduler jdbcScheduler() {
        // Creates a dedicated, named thread pool for blocking (JDBC) reactive tasks
        return Schedulers.newBoundedElastic(10, Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE, "jdbc-io-");
    }
}