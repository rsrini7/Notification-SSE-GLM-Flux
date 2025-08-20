package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface CacheService {

    void registerUserConnection(String userId, String connectionId, String podId, String clusterName);

    void unregisterUserConnection(String userId, String connectionId);
    Map<String, UserConnectionInfo> getConnectionsForUser(String userId);
    boolean isUserOnline(String userId);
    void updateHeartbeats(Set<String> connectionIds);
    Set<String> getStaleConnectionIds(long thresholdTimestamp);
    Optional<UserConnectionInfo> getConnectionDetails(String connectionId);
    void removeConnections(Set<String> connectionIds);
    long getTotalActiveUsers();
    long getPodActiveUsers(String podId);

    List<String> getOnlineUsers();
    void cacheUserMessages(String userId, List<PersistentUserMessageInfo> messages);
    List<PersistentUserMessageInfo> getCachedUserMessages(String userId);
    void addMessageToUserCache(String userId, PersistentUserMessageInfo message);
    void removeMessageFromUserCache(String userId, Long broadcastId);
    void cachePendingEvent(MessageDeliveryEvent event);
    List<MessageDeliveryEvent> getPendingEvents(String userId);
    void removePendingEvent(String userId, Long broadcastId);
    void clearPendingEvents(String userId);
    Map<String, Object> getCacheStats();
    Optional<BroadcastMessage> getBroadcastContent(Long broadcastId);
    void cacheBroadcastContent(BroadcastMessage broadcast);
    void evictBroadcastContent(Long broadcastId);
    List<BroadcastMessage> getActiveGroupBroadcasts(String cacheKey);
    void cacheActiveGroupBroadcasts(String cacheKey, List<BroadcastMessage> broadcasts);
    void evictActiveGroupBroadcastsCache();
}