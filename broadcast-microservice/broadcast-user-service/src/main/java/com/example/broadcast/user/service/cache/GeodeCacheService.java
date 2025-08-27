package com.example.broadcast.user.service.cache;

import com.example.broadcast.shared.dto.cache.ConnectionHeartbeat;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import com.example.broadcast.shared.model.BroadcastMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
@Profile("!checkpoint-build") 
public class GeodeCacheService implements CacheService {

    private final ClientCache clientCache;
    private final Region<String, UserConnectionInfo> userConnectionsRegion;
    private final Region<String, ConnectionHeartbeat> connectionHeartbeatRegion;
    private final Region<String, List<UserMessageInbox>> userMessagesInboxRegion;
    private final Region<Long, BroadcastMessage> broadcastContentRegion;

    public GeodeCacheService(ClientCache clientCache,
                             @Qualifier("userConnectionsRegion") Region<String, UserConnectionInfo> userConnectionsRegion,
                             @Qualifier("connectionHeartbeatRegion") Region<String, ConnectionHeartbeat> connectionHeartbeatRegion,
                             @Qualifier("userMessagesInboxRegion") Region<String, List<UserMessageInbox>> userMessagesInboxRegion,
                             @Qualifier("broadcastContentRegion") Region<Long, BroadcastMessage> broadcastContentRegion
    ) {
        this.clientCache = clientCache;
        this.userConnectionsRegion = userConnectionsRegion;
        this.connectionHeartbeatRegion = connectionHeartbeatRegion;
        this.userMessagesInboxRegion = userMessagesInboxRegion;
        this.broadcastContentRegion = broadcastContentRegion;
    }


    @Override
    public void registerUserConnection(String userId, String connectionId, String podName, String clusterName) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        UserConnectionInfo userConnectionInfo = new UserConnectionInfo(userId, connectionId, podName, clusterName, now, now);

        userConnectionsRegion.put(userId, userConnectionInfo);

        ConnectionHeartbeat metadata = new ConnectionHeartbeat(userId, now.toEpochSecond());
        connectionHeartbeatRegion.put(connectionId, metadata);
    }

    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
       
        // This method is now problematic as we don't know the clusterName here.
        // The calling context should be more specific. We assume the info object provides it.
        // Note: This highlights that clusterName needs to be available in most contexts.
        getConnectionInfo(connectionId).ifPresent(info -> {
            userConnectionsRegion.remove(userId);
        });
        
        connectionHeartbeatRegion.remove(connectionId);
    }
    
    @Override
    public boolean isUserOnline(String userId) {
        return userConnectionsRegion.containsKey(userId);
    }

    @Override
    public void updateHeartbeats(Set<String> connectionIds) {
        long now = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        Map<String, ConnectionHeartbeat> updates = new HashMap<>();
        for (String connId : connectionIds) {
            ConnectionHeartbeat currentMeta = connectionHeartbeatRegion.get(connId);
            if (currentMeta != null) {
                // Create a new object with the updated timestamp
                updates.put(connId, currentMeta.withLastHeartbeatTimestamp(now));
            }
        }
        if (!updates.isEmpty()) {
            connectionHeartbeatRegion.putAll(updates);
        }
    }

    @Override
    public Set<String> getStaleConnectionIds(long thresholdTimestamp) {
        return connectionHeartbeatRegion.entrySet().stream()
                .filter(entry -> entry.getValue().getLastHeartbeatTimestamp() < thresholdTimestamp)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    @Override
    public Optional<UserConnectionInfo> getConnectionDetails(String connectionId) {
        return getConnectionInfo(connectionId);
    }

    @Override
    public void removeConnections(Set<String> connectionIds) {
        connectionIds.forEach(connId -> {
            // NEW LOGIC: Get userId from the metadata object
            ConnectionHeartbeat metadata = connectionHeartbeatRegion.get(connId);
            if (metadata != null) {
                unregisterUserConnection(metadata.getUserId(), connId);
            }
        });
    }
    
    private Optional<UserConnectionInfo> getConnectionInfo(String connectionId) {
        // NEW LOGIC: Get userId and clusterName from the metadata object
        ConnectionHeartbeat metadata = connectionHeartbeatRegion.get(connectionId);
        if (metadata == null) {
            return Optional.empty();
        }
        
        return Optional.of(userConnectionsRegion.get(metadata.getUserId()));
    }

    @Override
    public Optional<List<UserMessageInbox>> getUserInbox(String userId) {
        return Optional.ofNullable(userMessagesInboxRegion.get(userId));
    }

    @Override
    public void cacheUserInbox(String userId, List<UserMessageInbox> inbox) {
        log.debug("Caching inbox for user: {}. Size: {}", userId, inbox.size());
        userMessagesInboxRegion.put(userId, inbox);
    }

    @Override
    public void evictUserInbox(String userId) {
        log.info("Evicting inbox cache for user: {}", userId);
        userMessagesInboxRegion.remove(userId);
    }

    @Override
    public Map<String, UserConnectionInfo> getConnectionsForUser(String userId) {
        return Optional.of(userConnectionsRegion.get(userId))
                .map(info -> Map.of(info.getConnectionId(), info))
                .orElse(Collections.emptyMap());
    }
    
    @Override
    public long getTotalActiveUsers() {
        return userConnectionsRegion.size(); // Use userConnectionsRegion for total count
    }

    @Override
    public List<String> getOnlineUsers() {
        return userConnectionsRegion.keySetOnServer().stream()
            .map(key -> key.substring(key.indexOf(':') + 1))
            .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalOnlineUsers", getTotalActiveUsers());
        stats.put("totalTrackedConnections", connectionHeartbeatRegion.size());
        stats.put("regionSizes", clientCache.rootRegions().stream()
                .collect(Collectors.toMap(Region::getName, Region::size)));
        return stats;
    }

    @Override
    public Optional<BroadcastMessage> getBroadcastContent(Long broadcastId) {
        return Optional.ofNullable(broadcastContentRegion.get(broadcastId));
    }

    @Override
    public void cacheBroadcastContent(BroadcastMessage broadcast) {
        if (broadcast != null) broadcastContentRegion.put(broadcast.getId(), broadcast);
    }

    @Override
    public void evictBroadcastContent(Long broadcastId) {
        if (broadcastId != null) broadcastContentRegion.remove(broadcastId);
    }
}