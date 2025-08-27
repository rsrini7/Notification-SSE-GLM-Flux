package com.example.broadcast.user.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.ConnectionMetadata;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class GeodeCacheService implements CacheService {

    private final ClientCache clientCache;
    private final Region<String, UserConnectionInfo> userConnectionsRegion;
    private final Region<String, ConnectionMetadata> connectionMetadataRegion;
    private final Region<String, List<MessageDeliveryEvent>> pendingEventsRegion;
    private final Region<Long, BroadcastMessage> broadcastContentRegion;

    public GeodeCacheService(ClientCache clientCache,
                             @Qualifier("userConnectionsRegion") Region<String, UserConnectionInfo> userConnectionsRegion,
                             @Qualifier("connectionMetadataRegion") Region<String, ConnectionMetadata> connectionMetadataRegion,
                             @Qualifier("pendingEventsRegion") Region<String, List<MessageDeliveryEvent>> pendingEventsRegion,
                             @Qualifier("broadcastContentRegion") Region<Long, BroadcastMessage> broadcastContentRegion
    ) {
        this.clientCache = clientCache;
        this.userConnectionsRegion = userConnectionsRegion;
        this.connectionMetadataRegion = connectionMetadataRegion;
        this.pendingEventsRegion = pendingEventsRegion;
        this.broadcastContentRegion = broadcastContentRegion;
    }


    @Override
    public void registerUserConnection(String userId, String connectionId, String podName, String clusterName) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        UserConnectionInfo userConnectionInfo = new UserConnectionInfo(userId, connectionId, podName, clusterName, now, now);

        userConnectionsRegion.put(getClusterUserKey(userId, clusterName), userConnectionInfo);

        ConnectionMetadata metadata = new ConnectionMetadata(userId, clusterName, now.toEpochSecond());
        connectionMetadataRegion.put(connectionId, metadata);
    }

    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
       
        // This method is now problematic as we don't know the clusterName here.
        // The calling context should be more specific. We assume the info object provides it.
        // Note: This highlights that clusterName needs to be available in most contexts.
        getConnectionInfo(connectionId).ifPresent(info -> {
            userConnectionsRegion.remove(getClusterUserKey(userId, info.getClusterName()));
        });
        
        connectionMetadataRegion.remove(connectionId);
    }
    
    @Override
    public boolean isUserOnline(String userId, String clusterName) {
        return userConnectionsRegion.containsKey(getClusterUserKey(userId, clusterName));
    }

    @Override
    public void updateHeartbeats(Set<String> connectionIds) {
        long now = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        Map<String, ConnectionMetadata> updates = new HashMap<>();
        for (String connId : connectionIds) {
            ConnectionMetadata currentMeta = connectionMetadataRegion.get(connId);
            if (currentMeta != null) {
                // Create a new object with the updated timestamp
                updates.put(connId, currentMeta.withLastHeartbeatTimestamp(now));
            }
        }
        if (!updates.isEmpty()) {
            connectionMetadataRegion.putAll(updates);
        }
    }

    @Override
    public Set<String> getStaleConnectionIds(long thresholdTimestamp) {
        return connectionMetadataRegion.entrySet().stream()
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
            ConnectionMetadata metadata = connectionMetadataRegion.get(connId);
            if (metadata != null) {
                unregisterUserConnection(metadata.getUserId(), connId);
            }
        });
    }
    
    private Optional<UserConnectionInfo> getConnectionInfo(String connectionId) {
        // NEW LOGIC: Get userId and clusterName from the metadata object
        ConnectionMetadata metadata = connectionMetadataRegion.get(connectionId);
        if (metadata == null) {
            return Optional.empty();
        }
        
        return Optional.of(userConnectionsRegion.get(getClusterUserKey(metadata.getUserId(),metadata.getClusterName())));
    }


    @Override
    public void cachePendingEvent(MessageDeliveryEvent event, String clusterName) {
        String key = getClusterUserKey(event.getUserId(), clusterName);
        List<MessageDeliveryEvent> pendingEvents = pendingEventsRegion.get(key);
        if (pendingEvents == null) {
            pendingEvents = new ArrayList<>();
        }
        pendingEvents.add(event);
        pendingEventsRegion.put(key, pendingEvents);
    }

    @Override
    public void removePendingEvent(String userId, Long broadcastId) {
        List<MessageDeliveryEvent> pendingEvents = pendingEventsRegion.get(userId);
        if (pendingEvents != null) {
            pendingEvents.removeIf(evt -> evt.getBroadcastId().equals(broadcastId));
            if (pendingEvents.isEmpty()) {
                pendingEventsRegion.remove(userId);
            } else {
                pendingEventsRegion.put(userId, pendingEvents);
            }
        }
    }

    @Override
    public Map<String, UserConnectionInfo> getConnectionsForUser(String userId, String clusterName) {
        return Optional.of(userConnectionsRegion.get(getClusterUserKey(userId, clusterName)))
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
    public List<MessageDeliveryEvent> getPendingEvents(String userId, String clusterName) {
        return pendingEventsRegion.getOrDefault(getClusterUserKey(userId, clusterName), Collections.emptyList());
    }

    @Override
    public void clearPendingEvents(String userId, String clusterName) {
        pendingEventsRegion.remove(getClusterUserKey(userId, clusterName));
    }

    @Override
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalOnlineUsers", getTotalActiveUsers());
        stats.put("totalTrackedConnections", connectionMetadataRegion.size());
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

    private String getClusterUserKey(String userId, String clusterName) {
        return clusterName + ":" + userId;
    }
}