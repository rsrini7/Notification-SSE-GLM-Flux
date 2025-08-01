package com.example.broadcast.service;

import com.example.broadcast.config.CaffeineConfig;
import com.example.broadcast.dto.MessageDeliveryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import com.github.benmanes.caffeine.cache.Cache;

import com.example.broadcast.util.Constants.ReadStatus;

/**
 * Service for managing Caffeine cache operations
 * Provides high-performance caching for user connections, messages, and pending events
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CaffeineCacheService {

    private final Cache<String, CaffeineConfig.UserConnectionInfo> userConnectionsCache;
    private final Cache<String, List<CaffeineConfig.UserMessageInfo>> userMessagesCache;
    private final Cache<String, List<CaffeineConfig.PendingEventInfo>> pendingEventsCache;
    private final Cache<String, CaffeineConfig.UserSessionInfo> userSessionCache;
    private final Cache<String, CaffeineConfig.BroadcastStatsInfo> broadcastStatsCache;

    // For tracking user online status
    private final ConcurrentHashMap<String, Boolean> onlineUsers = new ConcurrentHashMap<>();

    /**
     * Register user connection
     * Called when user establishes SSE connection
     */
    public void registerUserConnection(String userId, String sessionId, String podId) {
        try {
            CaffeineConfig.UserConnectionInfo connectionInfo = new CaffeineConfig.UserConnectionInfo(
                    userId, sessionId, podId, ZonedDateTime.now(), ZonedDateTime.now());
            
            userConnectionsCache.put(userId, connectionInfo);
            onlineUsers.put(userId, true);
            
            // Also update session cache
            CaffeineConfig.UserSessionInfo sessionInfo = new CaffeineConfig.UserSessionInfo(
                    userId, sessionId, podId, ZonedDateTime.now());
            userSessionCache.put(sessionId, sessionInfo);
            
            log.debug("User connection registered: {} on pod {}", userId, podId);
            
        } catch (Exception e) {
            log.error("Error registering user connection for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Unregister user connection
     * Called when user disconnects or session expires
     */
    public void unregisterUserConnection(String userId, String sessionId) {
        try {
            userConnectionsCache.invalidate(userId);
            onlineUsers.remove(userId);
            userSessionCache.invalidate(sessionId);
            
            log.debug("User connection unregistered: {}", userId);
            
        } catch (Exception e) {
            log.error("Error unregistering user connection for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Update user activity
     * Called on heartbeat or user interaction
     */
    public void updateUserActivity(String userId) {
        try {
            CaffeineConfig.UserConnectionInfo connectionInfo = userConnectionsCache.getIfPresent(userId);
            if (connectionInfo != null) {
                CaffeineConfig.UserConnectionInfo updatedInfo = new CaffeineConfig.UserConnectionInfo(
                        connectionInfo.getUserId(),
                        connectionInfo.getSessionId(),
                        connectionInfo.getPodId(),
                        connectionInfo.getConnectedAt(),
                        ZonedDateTime.now());
                
                userConnectionsCache.put(userId, updatedInfo);
            }
            
        } catch (Exception e) {
            log.error("Error updating user activity for {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Check if user is online
     */
    public boolean isUserOnline(String userId) {
        return onlineUsers.getOrDefault(userId, false);
    }

    /**
     * Get user connection info
     */
    public CaffeineConfig.UserConnectionInfo getUserConnectionInfo(String userId) {
        return userConnectionsCache.getIfPresent(userId);
    }

    /**
     * Get all online users
     */
    public List<String> getOnlineUsers() {
        return new ArrayList<>(onlineUsers.keySet());
    }

    /**
     * Get online users count by pod
     */
    public long getOnlineUsersCountByPod(String podId) {
        return userConnectionsCache.asMap().values().stream()
                .filter(info -> info.getPodId().equals(podId))
                .count();
    }

    /**
     * Cache user messages
     * Reduces database load for frequently accessed messages
     */
    public void cacheUserMessages(String userId, List<CaffeineConfig.UserMessageInfo> messages) {
        try {
            userMessagesCache.put(userId, messages);
            log.debug("Cached {} messages for user {}", messages.size(), userId);
            
        } catch (Exception e) {
            log.error("Error caching messages for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Get cached user messages
     */
    public List<CaffeineConfig.UserMessageInfo> getCachedUserMessages(String userId) {
        return userMessagesCache.getIfPresent(userId);
    }

    /**
     * Add message to user cache
     */
    public void addMessageToUserCache(String userId, CaffeineConfig.UserMessageInfo message) {
        try {
            List<CaffeineConfig.UserMessageInfo> messages = userMessagesCache.getIfPresent(userId);
            if (messages == null) {
                messages = new ArrayList<>();
            }
            
            messages.add(message);
            userMessagesCache.put(userId, messages);
            
        } catch (Exception e) {
            log.error("Error adding message to cache for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Remove message from user cache
     */
    public void removeMessageFromUserCache(String userId, Long messageId) {
        try {
            List<CaffeineConfig.UserMessageInfo> messages = userMessagesCache.getIfPresent(userId);
            if (messages != null) {
                messages.removeIf(msg -> msg.getMessageId().equals(messageId));
                userMessagesCache.put(userId, messages);
            }
            
        } catch (Exception e) {
            log.error("Error removing message from cache for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Cache pending event for offline user
     */
    public void cachePendingEvent(MessageDeliveryEvent event) {
        try {
            String userId = event.getUserId();
            List<CaffeineConfig.PendingEventInfo> pendingEvents = pendingEventsCache.getIfPresent(userId);
            
            if (pendingEvents == null) {
                pendingEvents = new ArrayList<>();
            }
            
            CaffeineConfig.PendingEventInfo pendingEvent = new CaffeineConfig.PendingEventInfo(
                    event.getEventId(),
                    event.getBroadcastId(),
                    event.getEventType(),
                    event.getTimestamp(),
                    event.getMessage());
            
            pendingEvents.add(pendingEvent);
            pendingEventsCache.put(userId, pendingEvents);
            
            log.debug("Cached pending event for user {}: {}", userId, event.getEventId());
            
        } catch (Exception e) {
            log.error("Error caching pending event: {}", e.getMessage());
        }
    }

    /**
     * Get pending events for user
     */
    public List<MessageDeliveryEvent> getPendingEvents(String userId) {
        try {
            List<CaffeineConfig.PendingEventInfo> pendingEvents = pendingEventsCache.getIfPresent(userId);
            if (pendingEvents == null) {
                return List.of();
            }
            
            return pendingEvents.stream()
                    .map(this::convertToMessageDeliveryEvent)
                    .toList();
                    
        } catch (Exception e) {
            log.error("Error getting pending events for user {}: {}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * Remove pending event
     */
    public void removePendingEvent(String userId, Long broadcastId) {
        try {
            List<CaffeineConfig.PendingEventInfo> pendingEvents = pendingEventsCache.getIfPresent(userId);
            if (pendingEvents != null) {
                pendingEvents.removeIf(event -> event.getBroadcastId().equals(broadcastId));
                pendingEventsCache.put(userId, pendingEvents);
            }
            
        } catch (Exception e) {
            log.error("Error removing pending event for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Clear pending events for user
     */
    public void clearPendingEvents(String userId) {
        try {
            pendingEventsCache.invalidate(userId);
            log.debug("Cleared pending events for user {}", userId);
            
        } catch (Exception e) {
            log.error("Error clearing pending events for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Update message read status in cache
     */
    public void updateMessageReadStatus(String userId, Long broadcastId) {
        try {
            List<CaffeineConfig.UserMessageInfo> messages = userMessagesCache.getIfPresent(userId);
            if (messages != null) {
                messages.stream()
                        .filter(msg -> msg.getBroadcastId().equals(broadcastId))
                        .forEach(msg -> {
                            // Update the message status (would need to create a new immutable object)
                            CaffeineConfig.UserMessageInfo updatedMsg = new CaffeineConfig.UserMessageInfo(
                                    msg.getMessageId(),
                                    msg.getBroadcastId(),
                                    msg.getContent(),
                                    msg.getPriority(),
                                    msg.getCreatedAt(),
                                    msg.getDeliveryStatus(),
                                    ReadStatus.READ.name());
                            
                            messages.remove(msg);
                            messages.add(updatedMsg);
                        });
                
                userMessagesCache.put(userId, messages);
            }
            
        } catch (Exception e) {
            log.error("Error updating message read status for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Cache broadcast statistics
     */
    public void cacheBroadcastStats(String statsKey, CaffeineConfig.BroadcastStatsInfo stats) {
        try {
            broadcastStatsCache.put(statsKey, stats);
            log.debug("Cached broadcast statistics for key: {}", statsKey);
            
        } catch (Exception e) {
            log.error("Error caching broadcast statistics: {}", e.getMessage());
        }
    }

    /**
     * Get cached broadcast statistics
     */
    public CaffeineConfig.BroadcastStatsInfo getCachedBroadcastStats(String statsKey) {
        return broadcastStatsCache.getIfPresent(statsKey);
    }

    /**
     * Get cache statistics
     */
    public java.util.Map<String, Object> getCacheStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        
        stats.put("userConnectionsCache", userConnectionsCache.stats());
        stats.put("userMessagesCache", userMessagesCache.stats());
        stats.put("pendingEventsCache", pendingEventsCache.stats());
        stats.put("userSessionCache", userSessionCache.stats());
        stats.put("broadcastStatsCache", broadcastStatsCache.stats());
        
        return stats;
    }

    /**
     * Clean up expired entries
     */
    public void cleanup() {
        try {
            // Clean up offline users
            onlineUsers.entrySet().removeIf(entry -> {
                CaffeineConfig.UserConnectionInfo connection = userConnectionsCache.getIfPresent(entry.getKey());
                if (connection == null || 
                    connection.getLastActivity().isBefore(ZonedDateTime.now().minusMinutes(5))) {
                    return true;
                }
                return false;
            });
            
            log.debug("Cache cleanup completed. Online users: {}", onlineUsers.size());
            
        } catch (Exception e) {
            log.error("Error during cache cleanup: {}", e.getMessage());
        }
    }

    /**
     * Convert pending event info to message delivery event
     */
    private MessageDeliveryEvent convertToMessageDeliveryEvent(CaffeineConfig.PendingEventInfo pendingEvent) {
        return MessageDeliveryEvent.builder()
                .eventId(pendingEvent.getEventId())
                .broadcastId(pendingEvent.getBroadcastId())
                .userId("") // Will be filled by caller
                .eventType(pendingEvent.getEventType())
                .podId("")
                .timestamp(pendingEvent.getTimestamp())
                .message(pendingEvent.getMessage())
                .build();
    }
}