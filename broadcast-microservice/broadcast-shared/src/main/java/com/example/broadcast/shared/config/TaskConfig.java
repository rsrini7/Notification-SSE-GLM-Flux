package com.example.broadcast.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
    @Primary
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
        /// Set a thread cap of 100 and a task queue cap for backpressure
        // int threadCap = 100;
        // int queuedTaskCap = 100000; // A high capacity for queued tasks
        // return Schedulers.newBoundedElastic(threadCap, queuedTaskCap, "jdbc-io-");

        // Schedulers.newParallel creates a fixed-size pool of standard platform threads.
        // This is ideal for I/O-bound tasks that need guaranteed parallelism,
        // bypassing the default Loom integration where it's causing serialization.
        int parallelism = 10; // Start with 10 parallel threads for DB operations
        return Schedulers.newParallel("jdbc-io-", parallelism);
    }

}