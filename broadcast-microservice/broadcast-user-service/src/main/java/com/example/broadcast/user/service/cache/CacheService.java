package com.example.broadcast.user.service.cache;

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
    Map<String, UserConnectionInfo> getConnectionsForUser(String userId, String podName);
    boolean isUserOnline(String userId, String podName);
    void updateHeartbeats(Set<String> connectionIds);

    Set<String> getStaleConnectionIds(long thresholdTimestamp);
    Optional<UserConnectionInfo> getConnectionDetails(String connectionId);
    void removeConnections(Set<String> connectionIds);

    long getTotalActiveUsers();
    List<String> getOnlineUsers();

    void cachePendingEvent(MessageDeliveryEvent event, String podName);
    List<MessageDeliveryEvent> getPendingEvents(String userId, String clusterName);
    void removePendingEvent(String userId, Long broadcastId);
    void clearPendingEvents(String userId, String podName);

    Map<String, Object> getCacheStats();

    Optional<BroadcastMessage> getBroadcastContent(Long broadcastId);
    void cacheBroadcastContent(BroadcastMessage broadcast);
    void evictBroadcastContent(Long broadcastId);

    Optional<List<UserMessageInbox>> getUserInbox(String userId);
    void cacheUserInbox(String userId, List<UserMessageInbox> inbox);
    void evictUserInbox(String userId);
 }