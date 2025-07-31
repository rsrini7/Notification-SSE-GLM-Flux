package com.example.broadcast.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
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
                .maximumSize(10000)
                .expireAfterAccess(Duration.ofMinutes(30))
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
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofMinutes(5))
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
                return Duration.ofMinutes(Long.parseLong(durationStr)); // Default to minutes
            }
        } catch (Exception e) {
            return Duration.ofMinutes(30); // Default fallback
        }
    }

    /**
     * Inner classes for cache data structures
     */
    public static class UserConnectionInfo {
        private final String userId;
        private final String sessionId;
        private final String podId;
        private final java.time.ZonedDateTime connectedAt;
        private final java.time.ZonedDateTime lastActivity;

        public UserConnectionInfo(String userId, String sessionId, String podId, 
                                 java.time.ZonedDateTime connectedAt, java.time.ZonedDateTime lastActivity) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.podId = podId;
            this.connectedAt = connectedAt;
            this.lastActivity = lastActivity;
        }

        // Getters
        public String getUserId() { return userId; }
        public String getSessionId() { return sessionId; }
        public String getPodId() { return podId; }
        public java.time.ZonedDateTime getConnectedAt() { return connectedAt; }
        public java.time.ZonedDateTime getLastActivity() { return lastActivity; }
    }

    public static class UserMessageInfo {
        private final Long messageId;
        private final Long broadcastId;
        private final String content;
        private final String priority;
        private final java.time.ZonedDateTime createdAt;
        private final String deliveryStatus;
        private final String readStatus;

        public UserMessageInfo(Long messageId, Long broadcastId, String content, String priority,
                              java.time.ZonedDateTime createdAt, String deliveryStatus, String readStatus) {
            this.messageId = messageId;
            this.broadcastId = broadcastId;
            this.content = content;
            this.priority = priority;
            this.createdAt = createdAt;
            this.deliveryStatus = deliveryStatus;
            this.readStatus = readStatus;
        }

        // Getters
        public Long getMessageId() { return messageId; }
        public Long getBroadcastId() { return broadcastId; }
        public String getContent() { return content; }
        public String getPriority() { return priority; }
        public java.time.ZonedDateTime getCreatedAt() { return createdAt; }
        public String getDeliveryStatus() { return deliveryStatus; }
        public String getReadStatus() { return readStatus; }
    }

    public static class PendingEventInfo {
        private final String eventId;
        private final Long broadcastId;
        private final String eventType;
        private final java.time.ZonedDateTime timestamp;
        private final String message;

        public PendingEventInfo(String eventId, Long broadcastId, String eventType,
                               java.time.ZonedDateTime timestamp, String message) {
            this.eventId = eventId;
            this.broadcastId = broadcastId;
            this.eventType = eventType;
            this.timestamp = timestamp;
            this.message = message;
        }

        // Getters
        public String getEventId() { return eventId; }
        public Long getBroadcastId() { return broadcastId; }
        public String getEventType() { return eventType; }
        public java.time.ZonedDateTime getTimestamp() { return timestamp; }
        public String getMessage() { return message; }
    }

    public static class UserSessionInfo {
        private final String userId;
        private final String sessionId;
        private final String podId;
        private final java.time.ZonedDateTime lastHeartbeat;

        public UserSessionInfo(String userId, String sessionId, String podId, java.time.ZonedDateTime lastHeartbeat) {
            this.userId = userId;
            this.sessionId = sessionId;
            this.podId = podId;
            this.lastHeartbeat = lastHeartbeat;
        }

        // Getters
        public String getUserId() { return userId; }
        public String getSessionId() { return sessionId; }
        public String getPodId() { return podId; }
        public java.time.ZonedDateTime getLastHeartbeat() { return lastHeartbeat; }
    }

    public static class BroadcastStatsInfo {
        private final Long broadcastId;
        private final Integer totalTargeted;
        private final Integer totalDelivered;
        private final Integer totalRead;
        private final java.time.ZonedDateTime calculatedAt;

        public BroadcastStatsInfo(Long broadcastId, Integer totalTargeted, Integer totalDelivered, 
                                 Integer totalRead, java.time.ZonedDateTime calculatedAt) {
            this.broadcastId = broadcastId;
            this.totalTargeted = totalTargeted;
            this.totalDelivered = totalDelivered;
            this.totalRead = totalRead;
            this.calculatedAt = calculatedAt;
        }

        // Getters
        public Long getBroadcastId() { return broadcastId; }
        public Integer getTotalTargeted() { return totalTargeted; }
        public Integer getTotalDelivered() { return totalDelivered; }
        public Integer getTotalRead() { return totalRead; }
        public java.time.ZonedDateTime getCalculatedAt() { return calculatedAt; }
    }
}