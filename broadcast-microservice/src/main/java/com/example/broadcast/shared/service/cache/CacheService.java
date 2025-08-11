package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CacheService {

    // CHANGED: Renamed sessionId to connectionId in method signatures
    void registerUserConnection(String userId, String connectionId, String podId);
    void unregisterUserConnection(String userId, String connectionId);

    void updateUserActivity(String userId);
    boolean isUserOnline(String userId);
    UserConnectionInfo getUserConnectionInfo(String userId);
    List<String> getOnlineUsers();
    void cacheUserMessages(String userId, List<UserMessageInfo> messages);
    List<UserMessageInfo> getCachedUserMessages(String userId);
    void addMessageToUserCache(String userId, UserMessageInfo message);
    void removeMessageFromUserCache(String userId, Long broadcastId);
    void cachePendingEvent(MessageDeliveryEvent event);
    List<MessageDeliveryEvent> getPendingEvents(String userId);
    void removePendingEvent(String userId, Long broadcastId);
    void clearPendingEvents(String userId);
    void cacheBroadcastStats(String statsKey, BroadcastStatsInfo stats);
    BroadcastStatsInfo getCachedBroadcastStats(String statsKey);
    Map<String, Object> getCacheStats();
    Optional<BroadcastMessage> getBroadcastContent(Long broadcastId);
    void cacheBroadcastContent(BroadcastMessage broadcast);
    void evictBroadcastContent(Long broadcastId);
    List<BroadcastMessage> getActiveGroupBroadcasts(String cacheKey);
    void cacheActiveGroupBroadcasts(String cacheKey, List<BroadcastMessage> broadcasts);
    void evictActiveGroupBroadcastsCache();
}