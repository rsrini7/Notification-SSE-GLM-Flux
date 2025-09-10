package com.example.broadcast.user.service.cache;

import com.example.broadcast.shared.dto.cache.ConnectionHeartbeat;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import com.example.broadcast.shared.dto.BroadcastContent;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GeodeCacheService implements CacheService {

    private final ClientCache clientCache;
    private final Region<String, Map<String, UserConnectionInfo>> userConnectionsRegion;
    private final Region<String, ConnectionHeartbeat> connectionHeartbeatRegion;
    private final Region<String, List<UserMessageInbox>> userMessagesInboxRegion;
    private final Region<Long, BroadcastContent> broadcastContentRegion;

    public GeodeCacheService(ClientCache clientCache,
                             @Qualifier("userConnectionsRegion") Region<String, Map<String, UserConnectionInfo>> userConnectionsRegion,
                             @Qualifier("connectionHeartbeatRegion") Region<String, ConnectionHeartbeat> connectionHeartbeatRegion,
                             @Qualifier("userMessagesInboxRegion") Region<String, List<UserMessageInbox>> userMessagesInboxRegion,
                             @Qualifier("broadcastContentRegion") Region<Long, BroadcastContent> broadcastContentRegion
    ) {
        this.clientCache = clientCache;
        this.userConnectionsRegion = userConnectionsRegion;
        this.connectionHeartbeatRegion = connectionHeartbeatRegion;
        this.userMessagesInboxRegion = userMessagesInboxRegion;
        this.broadcastContentRegion = broadcastContentRegion;
    }

    @Override
    public void registerUserConnection(String userId, String connectionId, String podName, String clusterName) {
        long nowEpochMilli = OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli();
        UserConnectionInfo newConnectionInfo = new UserConnectionInfo(userId, connectionId, podName, clusterName, nowEpochMilli, nowEpochMilli);

        // Atomically update the user's connection map
        userConnectionsRegion.compute(userId, (key, existingConnections) -> {
            if (existingConnections == null) {
                existingConnections = new HashMap<>();
            }
            existingConnections.put(connectionId, newConnectionInfo);
            return existingConnections;
        });
        
        // Heartbeat logic remains the same
        ConnectionHeartbeat metadata = new ConnectionHeartbeat(userId, nowEpochMilli);
        connectionHeartbeatRegion.put(connectionId, metadata);
    }

    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
        // Atomically update the user's connection map
        userConnectionsRegion.compute(userId, (key, existingConnections) -> {
            if (existingConnections != null) {
                existingConnections.remove(connectionId);
                // If the map is now empty, remove the user's entry completely
                if (existingConnections.isEmpty()) {
                    return null; 
                }
            }
            return existingConnections;
        });

        connectionHeartbeatRegion.remove(connectionId);
    }
    
    @Override
    public boolean isUserOnline(String userId) {
        return userConnectionsRegion.containsKey(userId);
    }

    @Override
    public void updateHeartbeats(Set<String> connectionIds) {
        long now = OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond();
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
        // Step 1: Find the userId from the heartbeat region (reverse lookup)
        ConnectionHeartbeat heartbeat = connectionHeartbeatRegion.get(connectionId);
        if (heartbeat == null) {
            log.debug("No heartbeat found for connectionId: {}, cannot get connection details.", connectionId);
            return Optional.empty();
        }

        String userId = heartbeat.getUserId();

        // Step 2: Get the map of all connections for that user from the userConnectionsRegion
        Map<String, UserConnectionInfo> userConnections = userConnectionsRegion.get(userId);
        if (userConnections == null || userConnections.isEmpty()) {
            log.warn("Heartbeat exists for connectionId {} (user {}), but no connection map found in userConnectionsRegion.", connectionId, userId);
            return Optional.empty();
        }

        // Step 3: Get the specific connection info from the map using the original connectionId
        return Optional.ofNullable(userConnections.get(connectionId));
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
        // This method now correctly returns all connections for the user
        Map<String, UserConnectionInfo> connections = userConnectionsRegion.get(userId);
        return connections != null ? connections : Collections.emptyMap();
    }
    
    @Override
    public long getTotalActiveUsers() {
        return userConnectionsRegion.size(); // Use userConnectionsRegion for total count
    }

    @Override
    public List<String> getOnlineUsers() {
        return userConnectionsRegion.keySetOnServer().stream().toList();
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
    public Optional<BroadcastContent> getBroadcastContent(Long broadcastId) {
        return Optional.ofNullable(broadcastContentRegion.get(broadcastId));
    }

    @Override
    public void cacheBroadcastContent(BroadcastContent broadcast) {
        if (broadcast != null) broadcastContentRegion.put(broadcast.getId(), broadcast);
    }

    @Override
    public void evictBroadcastContent(Long broadcastId) {
        if (broadcastId != null) broadcastContentRegion.remove(broadcastId);
    }
}