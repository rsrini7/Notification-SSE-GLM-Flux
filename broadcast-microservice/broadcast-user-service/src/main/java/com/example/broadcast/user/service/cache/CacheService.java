package com.example.broadcast.user.service.cache;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.dto.BroadcastContent;
import com.example.broadcast.shared.dto.cache.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Monitored("cache")
public interface CacheService {

    boolean registerUserConnection(String userId, String connectionId, String podId, String clusterName);
    void unregisterUserConnection(String userId, String connectionId);
    Map<String, UserConnectionInfo> getConnectionsForUser(String userId);
    boolean isUserOnline(String userId);
    void updateHeartbeats(Set<String> connectionIds);
    Optional<ConnectionHeartbeat> getHeartbeatEntry(String connectionId);

    Set<String> getStaleConnectionIds(long thresholdTimestamp);
    Optional<UserConnectionInfo> getConnectionDetails(String connectionId);
    void removeConnections(Set<String> connectionIds);

    long getTotalActiveUsers();
    List<String> getOnlineUsers();

    Map<String, Object> getCacheStats();

    Optional<BroadcastContent> getBroadcastContent(Long broadcastId);
    void cacheBroadcastContent(BroadcastContent broadcast);
    void evictBroadcastContent(Long broadcastId);

    Optional<List<UserMessageInbox>> getUserInbox(String userId);
    void cacheUserInbox(String userId, List<UserMessageInbox> inbox);
    void evictUserInbox(String userId);
 }