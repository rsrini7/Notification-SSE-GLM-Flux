package com.example.broadcast.user.health;

import com.example.broadcast.user.service.SseService;
import com.example.broadcast.user.service.cache.CacheService;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Custom health indicator for broadcast microservice
 * Provides detailed health status for all system components
 */
@Component
public class BroadcastHealthIndicator implements HealthIndicator {

    private final SseService sseService;
    private final CacheService cacheService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public BroadcastHealthIndicator(SseService sseService, 
                                  CacheService cacheService,
                                  KafkaTemplate<String, Object> kafkaTemplate) {
        this.sseService = sseService;
        this.cacheService = cacheService;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        
        // Check SSE connections
        boolean sseHealthy = checkSseConnections(details);
        
        // Check cache health
        boolean cacheHealthy = checkCacheHealth(details);
        
        // Check Kafka connectivity
        boolean kafkaHealthy = checkKafkaConnectivity(details);
        
        // Overall health status
        boolean overallHealthy = sseHealthy && cacheHealthy && kafkaHealthy;
        
        Health.Builder healthBuilder = overallHealthy ? 
                Health.up() : Health.down();
        
        return healthBuilder
                .withDetails(details)
                .build();
    }

    /**
     * Check SSE connections health
     */
    private boolean checkSseConnections(Map<String, Object> details) {
        try {
            int connectedUsers = sseService.getConnectedUserCount();
            details.put("sseConnections", connectedUsers);
            details.put("sseStatus", "UP");
            
            // Consider healthy if we can get connection count
            return connectedUsers >= 0;
            
        } catch (Exception e) {
            details.put("sseStatus", "DOWN");
            details.put("sseError", e.getMessage());
            return false;
        }
    }

    /**
     * Check cache health
     */
    private boolean checkCacheHealth(Map<String, Object> details) {
        try {
            Map<String, Object> cacheStats = cacheService.getCacheStats();
            details.put("cacheStats", cacheStats);
            details.put("cacheStatus", "UP");
            
            // Consider healthy if we can get cache stats
            return cacheStats != null;
            
        } catch (Exception e) {
            details.put("cacheStatus", "DOWN");
            details.put("cacheError", e.getMessage());
            return false;
        }
    }

    /**
     * Check Kafka connectivity
     */
    private boolean checkKafkaConnectivity(Map<String, Object> details) {
        try {
            // Try to send a test message to a test topic
            kafkaTemplate.send("broadcast-health-check", "test-key", "test-message")
                    .get(); // Wait for send completion
            
            details.put("kafkaStatus", "UP");
            details.put("kafkaBroker", "Connected");
            return true;
            
        } catch (Exception e) {
            details.put("kafkaStatus", "DOWN");
            details.put("kafkaError", e.getMessage());
            return false;
        }
    }

    /**
     * Get detailed health information
     */
    public Map<String, Object> getDetailedHealth() {
        Map<String, Object> health = new HashMap<>();
        
        // SSE Health
        Map<String, Object> sseHealth = new HashMap<>();
        sseHealth.put("connectedUsers", sseService.getConnectedUserCount());
        sseHealth.put("status", "UP");
        health.put("sse", sseHealth);
        
        // Cache Health
        Map<String, Object> cacheHealth = new HashMap<>();
        cacheHealth.put("stats", cacheService.getCacheStats());
        cacheHealth.put("status", "UP");
        health.put("cache", cacheHealth);
        
        // Kafka Health
        Map<String, Object> kafkaHealth = new HashMap<>();
        kafkaHealth.put("status", "UP");
        kafkaHealth.put("broker", "Connected");
        health.put("kafka", kafkaHealth);
        
        // Overall Status
        health.put("status", "UP");
        health.put("timestamp", System.currentTimeMillis());
        
        return health;
    }
}