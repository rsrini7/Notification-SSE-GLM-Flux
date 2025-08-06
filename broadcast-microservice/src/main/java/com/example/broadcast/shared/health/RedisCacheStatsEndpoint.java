package com.example.broadcast.shared.health;

import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.service.cache.RedisCacheService;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Endpoint(id = "redis-cache-stats") // This sets the URL to /actuator/redis-cache-stats
@Profile("redis") // Only activate this endpoint when the Redis profile is active
@RequiredArgsConstructor
public class RedisCacheStatsEndpoint {

    private final CacheService cacheService;

    @ReadOperation // This annotation makes the method handle GET requests
    public Map<String, Object> getRedisCacheStats() {
        // We ensure we have the correct Redis-specific service instance
        if (cacheService instanceof RedisCacheService) {
            return cacheService.getCacheStats();
        }
        // Return an error if this endpoint is somehow accessed without the correct service
        return Map.of("error", "The active cache service is not RedisCacheService.");
    }
}