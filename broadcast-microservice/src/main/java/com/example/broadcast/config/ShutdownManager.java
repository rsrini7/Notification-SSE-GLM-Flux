package com.example.broadcast.config;

import com.example.broadcast.service.KafkaConsumerService;
import com.example.broadcast.service.SseService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Handles graceful shutdown of application components.
 * This component listens for the application context closing and ensures
 * that critical resources like thread pools are shut down cleanly.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ShutdownManager {

    private final KafkaConsumerService kafkaConsumerService;
    private final SseService sseService;

    @PreDestroy
    public void onShutdown() {
        log.info("Initiating graceful shutdown...");

        // Shutdown Kafka Consumer thread pool
        shutdownExecutorService(kafkaConsumerService.getExecutorService(), "KafkaConsumerService");

        // Shutdown SSE Service scheduler
        shutdownExecutorService(sseService.getScheduler(), "SseService");

        log.info("Graceful shutdown completed.");
    }

    /**
     * A helper method to shut down an ExecutorService gracefully.
     * @param executorService The executor service to shut down.
     * @param serviceName The name of the service for logging purposes.
     */
    private void shutdownExecutorService(ExecutorService executorService, String serviceName) {
        log.info("Shutting down ExecutorService for {}...", serviceName);
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown(); // Disable new tasks from being submitted
            try {
                // Wait a while for existing tasks to terminate
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.warn("ExecutorService for {} did not terminate in 60 seconds. Forcing shutdown...", serviceName);
                    executorService.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                        log.error("ExecutorService for {} did not terminate.", serviceName);
                    }
                }
            } catch (InterruptedException ie) {
                // (Re-)Cancel if current thread also interrupted
                log.error("Shutdown of ExecutorService for {} was interrupted.", serviceName, ie);
                executorService.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }
    }
}
