package com.example.broadcast.user.service.cache;

import com.example.broadcast.shared.dto.cache.ConnectionHeartbeat;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import com.example.broadcast.shared.config.AppProperties;
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
    private final AppProperties appProperties;

    public GeodeCacheService(ClientCache clientCache,
                             @Qualifier("userConnectionsRegion") Region<String, Map<String, UserConnectionInfo>> userConnectionsRegion,
                             @Qualifier("connectionHeartbeatRegion") Region<String, ConnectionHeartbeat> connectionHeartbeatRegion,
                             @Qualifier("userMessagesInboxRegion") Region<String, List<UserMessageInbox>> userMessagesInboxRegion,
                             @Qualifier("broadcastContentRegion") Region<Long, BroadcastContent> broadcastContentRegion,
                             AppProperties appProperties
    ) {
        this.clientCache = clientCache;
        this.userConnectionsRegion = userConnectionsRegion;
        this.connectionHeartbeatRegion = connectionHeartbeatRegion;
        this.userMessagesInboxRegion = userMessagesInboxRegion;
        this.broadcastContentRegion = broadcastContentRegion;
        this.appProperties = appProperties;
    }

    @Override
    public boolean registerUserConnection(String userId, String connectionId, String podName, String clusterName) {
        int maxRetries = 5; // Prevent an infinite loop in high-contention scenarios
        for (int i = 0; i < maxRetries; i++) {
            
            // 1. GET: Read the current value from Geode.
            Map<String, UserConnectionInfo> oldConnections = userConnectionsRegion.get(userId);
            
            int currentSize = (oldConnections == null) ? 0 : oldConnections.size();
            if (currentSize >= appProperties.getSse().getMaxConnectionsPerUser()) {
                log.warn("Connection limit reached for user '{}'. Registration failed.", userId);
                return false; // Limit reached
            }

            // 2. MODIFY: Create the new connection and add it to a new map.
            long nowEpochMilli = OffsetDateTime.now(ZoneOffset.UTC).toInstant().toEpochMilli();
            UserConnectionInfo newConnectionInfo = new UserConnectionInfo(userId, connectionId, podName, clusterName, nowEpochMilli, nowEpochMilli);
            Map<String, UserConnectionInfo> newConnections = (oldConnections == null) ? new HashMap<>() : new HashMap<>(oldConnections);
            newConnections.put(connectionId, newConnectionInfo);

            // 3. ATOMIC REPLACE (Compare-and-Set)
            boolean success;
            if (oldConnections == null) {
                success = (userConnectionsRegion.putIfAbsent(userId, newConnections) == null);
            } else {
                success = userConnectionsRegion.replace(userId, oldConnections, newConnections);
            }

            if (success) {
                log.info("Successfully registered connection {} for user '{}'", connectionId, userId);
                ConnectionHeartbeat metadata = new ConnectionHeartbeat(userId, nowEpochMilli);
                connectionHeartbeatRegion.put(connectionId, metadata);
                return true; // Success! Exit the method.
            }
            
            // If success is false, another thread updated the data. Loop to retry.
            log.warn("Optimistic locking conflict for user '{}'. Retrying... (attempt {}/{})", userId, i + 1, maxRetries);
        }
        
        // If the loop finishes, all retries have failed.
        log.error("Failed to register connection for user {} after {} attempts.", userId, maxRetries);
        return false;
    }

    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
    log.info("Synchronously unregistering connection {} for user {}", connectionId, userId);
    try {
        // GET the current map of connections for the user.
        Map<String, UserConnectionInfo> existingConnections = userConnectionsRegion.get(userId);

        if (existingConnections != null) {
            // MODIFY the map in local memory.
            Map<String, UserConnectionInfo> updatedConnections = new HashMap<>(existingConnections);
            updatedConnections.remove(connectionId);

            if (updatedConnections.isEmpty()) {
                // If the map is now empty, REMOVE the entire entry for the user.
                userConnectionsRegion.remove(userId);
                log.info("Removed last connection for user '{}'. User key is now removed from region.", userId);
            } else {
                // Otherwise, PUT the updated map back into the region.
                userConnectionsRegion.put(userId, updatedConnections);
                log.info("Removed connection {} for user '{}'. User now has {} connections.", connectionId, userId, updatedConnections.size());
            }
        }

        // Always remove the individual connection's heartbeat.
        connectionHeartbeatRegion.remove(connectionId);
        log.info("Synchronously completed unregister for connection {}", connectionId);

    } catch (Exception e) {
        log.error("Error during synchronous unregister for connection {}: {}", connectionId, e.getMessage());
    }
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