package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.util.Constants.ReadStatus;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Profile("!redis")
@RequiredArgsConstructor
@Slf4j
public class DefaultCacheService implements CacheService {

    private final Cache<String, UserConnectionInfo> userConnectionsCache;
    private final Cache<String, List<UserMessageInfo>> userMessagesCache;
    private final Cache<String, List<PendingEventInfo>> pendingEventsCache;
    private final Cache<String, UserSessionInfo> userSessionCache;
    private final Cache<String, BroadcastStatsInfo> broadcastStatsCache;
    private final ConcurrentHashMap<String, Boolean> onlineUsers = new ConcurrentHashMap<>();

    // ... (methods before getPendingEvents are unchanged)
    @Override
    public void registerUserConnection(String userId, String sessionId, String podId) {
        UserConnectionInfo connectionInfo = new UserConnectionInfo(userId, sessionId, podId, ZonedDateTime.now(), ZonedDateTime.now());
        userConnectionsCache.put(userId, connectionInfo);
        onlineUsers.put(userId, true);
        
        UserSessionInfo sessionInfo = new UserSessionInfo(userId, sessionId, podId, ZonedDateTime.now());
        userSessionCache.put(sessionId, sessionInfo);
        log.debug("User connection registered in Caffeine: {} on pod {}", userId, podId);
    }

    @Override
    public void unregisterUserConnection(String userId, String sessionId) {
        userConnectionsCache.invalidate(userId);
        onlineUsers.remove(userId);
        userSessionCache.invalidate(sessionId);
        log.debug("User connection unregistered from Caffeine: {}", userId);
    }

    @Override
    public void updateUserActivity(String userId) {
        UserConnectionInfo connectionInfo = userConnectionsCache.getIfPresent(userId);
        if (connectionInfo != null) {
            UserConnectionInfo updatedInfo = new UserConnectionInfo(
                connectionInfo.getUserId(), connectionInfo.getSessionId(), connectionInfo.getPodId(),
                connectionInfo.getConnectedAt(), ZonedDateTime.now()
            );
            userConnectionsCache.put(userId, updatedInfo);
        }
    }

    @Override
    public boolean isUserOnline(String userId) {
        return onlineUsers.getOrDefault(userId, false);
    }

    @Override
    public UserConnectionInfo getUserConnectionInfo(String userId) {
        return userConnectionsCache.getIfPresent(userId);
    }

    @Override
    public List<String> getOnlineUsers() {
        return new ArrayList<>(onlineUsers.keySet());
    }

    @Override
    public void cacheUserMessages(String userId, List<UserMessageInfo> messages) {
        userMessagesCache.put(userId, messages);
    }

    @Override
    public List<UserMessageInfo> getCachedUserMessages(String userId) {
        return userMessagesCache.getIfPresent(userId);
    }

    @Override
    public void addMessageToUserCache(String userId, UserMessageInfo message) {
        List<UserMessageInfo> messages = userMessagesCache.get(userId, k -> new ArrayList<>());
        messages.add(message);
        userMessagesCache.put(userId, messages);
    }

    @Override
    public void removeMessageFromUserCache(String userId, Long messageId) {
        List<UserMessageInfo> messages = userMessagesCache.getIfPresent(userId);
        if (messages != null) {
            messages.removeIf(msg -> msg.getMessageId().equals(messageId));
            userMessagesCache.put(userId, messages);
        }
    }

    @Override
    public void cachePendingEvent(MessageDeliveryEvent event) {
        String userId = event.getUserId();
        PendingEventInfo pendingEvent = new PendingEventInfo(event.getEventId(), event.getBroadcastId(), event.getEventType(), event.getTimestamp(), event.getMessage());
        List<PendingEventInfo> pendingEvents = pendingEventsCache.get(userId, k -> new ArrayList<>());
        pendingEvents.add(pendingEvent);
        pendingEventsCache.put(userId, pendingEvents);
    }

    @Override
    public List<MessageDeliveryEvent> getPendingEvents(String userId) {
        List<PendingEventInfo> pendingEvents = pendingEventsCache.getIfPresent(userId);
        if (pendingEvents == null) return List.of();
        
        return pendingEvents.stream()
            .map(p -> new MessageDeliveryEvent(p.getEventId(), p.getBroadcastId(), userId, p.getEventType(), null, p.getTimestamp(), p.getMessage(), null,false))
            .collect(Collectors.toList());
    }
    
    @Override
    public void removePendingEvent(String userId, Long broadcastId) {
         List<PendingEventInfo> pendingEvents = pendingEventsCache.getIfPresent(userId);
        if (pendingEvents != null) {
            pendingEvents.removeIf(event -> event.getBroadcastId().equals(broadcastId));
            pendingEventsCache.put(userId, pendingEvents);
        }
    }

    @Override
    public void clearPendingEvents(String userId) {
        pendingEventsCache.invalidate(userId);
    }

    @Override
    public void updateMessageReadStatus(String userId, Long broadcastId) {
        List<UserMessageInfo> messages = userMessagesCache.getIfPresent(userId);
        if (messages != null) {
            List<UserMessageInfo> updatedMessages = messages.stream()
                .map(msg -> {
                    if (msg.getBroadcastId().equals(broadcastId)) {
                        return new UserMessageInfo(msg.getMessageId(), msg.getBroadcastId(), msg.getContent(), msg.getPriority(), msg.getCreatedAt(), msg.getDeliveryStatus(), ReadStatus.READ.name());
                    }
                    return msg;
                }).collect(Collectors.toList());
            userMessagesCache.put(userId, updatedMessages);
        }
    }

    @Override
    public void cacheBroadcastStats(String statsKey, BroadcastStatsInfo stats) {
        broadcastStatsCache.put(statsKey, stats);
    }

    @Override
    public BroadcastStatsInfo getCachedBroadcastStats(String statsKey) {
        return broadcastStatsCache.getIfPresent(statsKey);
    }

    @Override
    public Map<String, Object> getCacheStats() {
        return Map.of(
            "userConnectionsCache", userConnectionsCache.stats(),
            "userMessagesCache", userMessagesCache.stats(),
            "pendingEventsCache", pendingEventsCache.stats(),
            "userSessionCache", userSessionCache.stats(),
            "broadcastStatsCache", broadcastStatsCache.stats()
        );
    }
}