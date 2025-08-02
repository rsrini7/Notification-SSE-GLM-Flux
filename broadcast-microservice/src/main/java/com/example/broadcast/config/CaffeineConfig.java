package com.example.broadcast.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Caffeine cache configuration for high-performance caching
 * Configures caches for user connections, messages, and session data
 */
@Configuration
@EnableCaching
public class CaffeineConfig {

    @Value("${broadcast.cache.user-connections.maximum-size:50000}")
    private int userConnectionsMaxSize;
    @Value("${broadcast.cache.user-connections.expire-after-write:1h}")
    private String userConnectionsExpireAfterWrite;

    @Value("${broadcast.cache.user-messages.maximum-size:100000}")
    private int userMessagesMaxSize;
    @Value("${broadcast.cache.user-messages.expire-after-write:24h}")
    private String userMessagesExpireAfterWrite;

    @Value("${broadcast.cache.pending-events.maximum-size:50000}")
    private int pendingEventsMaxSize;
    @Value("${broadcast.cache.pending-events.expire-after-write:6h}")
    private String pendingEventsExpireAfterWrite;

    @Value("${broadcast.cache.user-session.maximum-size:10000}")
    private int userSessionMaxSize;
    @Value("${broadcast.cache.user-session.expire-after-access:30m}")
    private String userSessionExpireAfterAccess;

    @Value("${broadcast.cache.broadcast-stats.maximum-size:1000}")
    private int broadcastStatsMaxSize;
    @Value("${broadcast.cache.broadcast-stats.expire-after-write:5m}")
    private String broadcastStatsExpireAfterWrite;

    /**
     * Cache for user connection mapping
     * Maps user ID to connection details for efficient SSE routing
     */
    @Bean
    public Cache<String, UserConnectionInfo> userConnectionsCache() {
        return Caffeine.newBuilder()
                .maximumSize(userConnectionsMaxSize)
                .expireAfterWrite(parseDuration(userConnectionsExpireAfterWrite))
                .recordStats()
                .build();
    }

    /**
     * Cache for user messages
     * Caches recent messages for quick access and reduces database load
     */
    @Bean
    public Cache<String, java.util.List<UserMessageInfo>> userMessagesCache() {
        return Caffeine.newBuilder()
                .maximumSize(userMessagesMaxSize)
                .expireAfterWrite(parseDuration(userMessagesExpireAfterWrite))
                .recordStats()
                .build();
    }

    /**
     * Cache for pending events
     * Stores events for offline users to ensure delivery when they come online
     */
    @Bean
    public Cache<String, java.util.List<PendingEventInfo>> pendingEventsCache() {
        return Caffeine.newBuilder()
                .maximumSize(pendingEventsMaxSize)
                .expireAfterWrite(parseDuration(pendingEventsExpireAfterWrite))
                .recordStats()
                .build();
    }

    /**
     * Cache for user session data
     * Tracks active user sessions and pod assignments
     */
    @Bean
    public Cache<String, UserSessionInfo> userSessionCache() {
        return Caffeine.newBuilder()
                .maximumSize(userSessionMaxSize)
                .expireAfterAccess(parseDuration(userSessionExpireAfterAccess))
                .recordStats()
                .build();
    }

    /**
     * Cache for broadcast statistics
     * Caches frequently accessed statistics to reduce database queries
     */
    @Bean
    public Cache<String, BroadcastStatsInfo> broadcastStatsCache() {
        return Caffeine.newBuilder()
                .maximumSize(broadcastStatsMaxSize)
                .expireAfterWrite(parseDuration(broadcastStatsExpireAfterWrite))
                .recordStats()
                .build();
    }

    /**
     * Helper method to parse duration strings
     */
    private Duration parseDuration(String durationStr) {
        try {
            if (durationStr.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(durationStr.substring(0, durationStr.length() - 2)));
            } else if (durationStr.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
            } else if (durationStr.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
            } else if (durationStr.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
            } else if (durationStr.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
            } else {
                return Duration.ofMinutes(Long.parseLong(durationStr));
                // Default to minutes
            }
        } catch (Exception e) {
            return Duration.ofMinutes(30);
            // Default fallback
        }
    }

    /**
     * Inner classes for cache data structures
     */
    @Getter
    @AllArgsConstructor
    public static class UserConnectionInfo {
        private final String userId;
        private final String sessionId;
        private final String podId;
        private final java.time.ZonedDateTime connectedAt;
        private final java.time.ZonedDateTime lastActivity;
    }

    @Getter
    @AllArgsConstructor
    public static class UserMessageInfo {
        private final Long messageId;
        private final Long broadcastId;
        private final String content;
        private final String priority;
        private final java.time.ZonedDateTime createdAt;
        private final String deliveryStatus;
        private final String readStatus;
    }

    @Getter
    @AllArgsConstructor
    public static class PendingEventInfo {
        private final String eventId;
        private final Long broadcastId;
        private final String eventType;
        private final java.time.ZonedDateTime timestamp;
        private final String message;
    }

    @Getter
    @AllArgsConstructor
    public static class UserSessionInfo {
        private final String userId;
        private final String sessionId;
        private final String podId;
        private final java.time.ZonedDateTime lastHeartbeat;
    }

    @Getter
    @AllArgsConstructor
    public static class BroadcastStatsInfo {
        private final Long broadcastId;
        private final Integer totalTargeted;
        private final Integer totalDelivered;
        private final Integer totalRead;
        private final java.time.ZonedDateTime calculatedAt;
    }
}