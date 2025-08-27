package com.example.broadcast.shared.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Configuration for monitoring, metrics, and distributed tracing
 * Provides comprehensive observability for the broadcast microservice
 */
@Configuration
@EnableAspectJAutoProxy
public class MonitoringConfig {

    /**
     * Custom metrics for broadcast operations
     */
    @Bean
    public MeterBinder broadcastMetrics() {
        return new MeterBinder() {
            @Override
            public void bindTo(MeterRegistry registry) {
                // Counter for broadcast messages created
                registry.counter("broadcast.messages.created", "type", "total");
                
                // Counter for message deliveries
                registry.counter("broadcast.messages.delivered", "status", "success");
                registry.counter("broadcast.messages.delivered", "status", "failed");
                
                // Counter for message reads
                registry.counter("broadcast.messages.read", "status", "success");
                
                // Gauge for active SSE connections
                AtomicLong sseConnections = new AtomicLong(0);
                registry.gauge("broadcast.sse.connections.active", sseConnections);
                
                // Gauge for pending messages
                AtomicLong pendingMessages = new AtomicLong(0);
                registry.gauge("broadcast.messages.pending", pendingMessages);
                
                // Timer for broadcast creation latency
                Timer.builder("broadcast.creation.latency")
                      .description("Time taken to create a broadcast")
                      .register(registry);
                
                // Timer for message delivery latency
                Timer.builder("broadcast.delivery.latency")
                      .description("Time taken to deliver a message")
                      .register(registry);
                
                // Timer for database operations
                Timer.builder("broadcast.database.latency")
                      .description("Time taken for database operations")
                      .tag("operation", "query")
                      .register(registry);
                
                Timer.builder("broadcast.database.latency")
                      .description("Time taken for database operations")
                      .tag("operation", "update")
                      .register(registry);
                
                // Cache metrics
                AtomicLong userConnectionsCache = new AtomicLong(0);
                AtomicLong userMessageInboxCache = new AtomicLong(0);
                
                registry.gauge("broadcast.cache.size.user.connections", userConnectionsCache);
                registry.gauge("broadcast.cache.size.pending.events", userMessageInboxCache);
                
                // Kafka metrics
                registry.counter("broadcast.kafka.producer.sent", "status", "success");
                registry.counter("broadcast.kafka.producer.sent", "status", "failed");
                registry.counter("broadcast.kafka.consumer.received", "status", "success");
                registry.counter("broadcast.kafka.consumer.received", "status", "failed");
                
                // Error metrics
                registry.counter("broadcast.errors", "type", "database");
                registry.counter("broadcast.errors", "type", "kafka");
                registry.counter("broadcast.errors", "type", "sse");
                registry.counter("broadcast.errors", "type", "cache");
            }
        };
    }

    /**
     * Custom metrics collector for real-time statistics
     */
    @Bean
    public BroadcastMetricsCollector broadcastMetricsCollector(MeterRegistry registry) {
        return new BroadcastMetricsCollector(registry);
    }

    /**
     * Component for collecting and managing custom metrics
     */
    public static class BroadcastMetricsCollector {
        private final MeterRegistry registry;
        private final ConcurrentHashMap<String, io.micrometer.core.instrument.Counter> counters = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Timer> timers = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

        public BroadcastMetricsCollector(MeterRegistry registry) {
            this.registry = registry;
        }

        public void incrementCounter(String name, String... tags) {
            String key = name + "_" + String.join("_", tags);
            counters.computeIfAbsent(key, k -> registry.counter(name, tags)).increment();
        }

        public void recordTimer(String name, long duration, String... tags) {
            String key = name + "_" + String.join("_", tags);
            timers.computeIfAbsent(key, k -> 
                Timer.builder(name).tags(tags).register(registry))
                  .record(duration, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        public void setGauge(String name, double value, String... tags) {
            String key = name + "_" + String.join("_", tags);
            AtomicLong gauge = gauges.computeIfAbsent(key, k -> {
                AtomicLong newGauge = new AtomicLong();
                registry.gauge(name, Tags.of(tags), newGauge);
                return newGauge;
            });
            gauge.set((long) value);
        }

        public long getCounterValue(String name, String... tags) {
            String key = name + "_" + String.join("_", tags);
            io.micrometer.core.instrument.Counter counter = counters.get(key);
            return counter != null ? (long) counter.count() : 0;
        }
    }
}