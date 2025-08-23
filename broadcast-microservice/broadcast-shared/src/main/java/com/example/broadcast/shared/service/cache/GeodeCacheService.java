package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.ConnectionMetadata;
import com.example.broadcast.shared.dto.cache.PersistentUserMessageInfo;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@Profile("!admin-only")
public class GeodeCacheService implements CacheService {

    private final ObjectMapper objectMapper;
    private final ClientCache clientCache;
    private final Region<String, String> userConnectionsRegion;
    private final Region<String, ConnectionMetadata> connectionMetadataRegion; // CONSOLIDATED
    private final Region<String, Set<String>> podConnectionsRegion;
    private final Region<String, List<MessageDeliveryEvent>> pendingEventsRegion;
    private final Region<Long, BroadcastMessage> broadcastContentRegion;
    private final Region<String, List<BroadcastMessage>> activeGroupBroadcastsRegion;
    private final Region<String, List<PersistentUserMessageInfo>> userMessagesRegion;

    public GeodeCacheService(ObjectMapper objectMapper,
                             ClientCache clientCache,
                             @Qualifier("userConnectionsRegion") Region<String, String> userConnectionsRegion,
                             @Qualifier("connectionMetadataRegion") Region<String, ConnectionMetadata> connectionMetadataRegion,
                             @Qualifier("podConnectionsRegion") Region<String, Set<String>> podConnectionsRegion,
                             @Qualifier("pendingEventsRegion") Region<String, List<MessageDeliveryEvent>> pendingEventsRegion,
                             @Qualifier("broadcastContentRegion") Region<Long, BroadcastMessage> broadcastContentRegion,
                             @Qualifier("activeGroupBroadcastsRegion") Region<String, List<BroadcastMessage>> activeGroupBroadcastsRegion,
                             @Qualifier("userMessagesRegion") Region<String, List<PersistentUserMessageInfo>> userMessagesRegion
    ) {
        this.objectMapper = objectMapper;
        this.clientCache = clientCache;
        this.userConnectionsRegion = userConnectionsRegion;
        this.connectionMetadataRegion = connectionMetadataRegion;
        this.podConnectionsRegion = podConnectionsRegion;
        this.pendingEventsRegion = pendingEventsRegion;
        this.broadcastContentRegion = broadcastContentRegion;
        this.activeGroupBroadcastsRegion = activeGroupBroadcastsRegion;
        this.userMessagesRegion = userMessagesRegion;
    }


    @Override
    public void registerUserConnection(String userId, String connectionId, String podId, String clusterName) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        UserConnectionInfo info = new UserConnectionInfo(userId, connectionId, podId, clusterName, now, now);
        try {
            String infoJson = objectMapper.writeValueAsString(info);
            userConnectionsRegion.put(userId, infoJson);

            // NEW LOGIC: Put a single metadata object
            ConnectionMetadata metadata = new ConnectionMetadata(userId, now.toEpochSecond());
            connectionMetadataRegion.put(connectionId, metadata);

            Set<String> podConnections = podConnectionsRegion.get(podId);
            if (podConnections == null) {
                podConnections = new HashSet<>();
            }
            podConnections.add(connectionId);
            podConnectionsRegion.put(podId, podConnections);

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize UserConnectionInfo", e);
        }
    }

    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
        getConnectionInfo(connectionId).ifPresent(info -> {
            String podId = info.getPodId();
            Set<String> podConnections = podConnectionsRegion.get(podId);
            if (podConnections != null) {
                podConnections.remove(connectionId);
                if (podConnections.isEmpty()) {
                    podConnectionsRegion.remove(podId);
                } else {
                    podConnectionsRegion.put(podId, podConnections);
                }
            }
        });
        userConnectionsRegion.remove(userId);
        // NEW LOGIC: Remove from the single metadata region
        connectionMetadataRegion.remove(connectionId);
    }
    
    @Override
    public boolean isUserOnline(String userId) {
        // NEW LOGIC: A user is online if an entry exists for them in the user-connections region.
        return userConnectionsRegion.containsKey(userId);
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
        // NEW LOGIC: Filter based on the timestamp within the metadata object
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
        // NEW LOGIC: Get userId from the metadata object
        ConnectionMetadata metadata = connectionMetadataRegion.get(connectionId);
        if (metadata == null) {
            return Optional.empty();
        }
        return getConnectionInfoByUserId(metadata.getUserId());
    }


    @Override
    public void addMessageToUserCache(String userId, PersistentUserMessageInfo message) {
        List<PersistentUserMessageInfo> messages = userMessagesRegion.get(userId);
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(0, message);
        userMessagesRegion.put(userId, messages);
    }

    @Override
    public void removeMessageFromUserCache(String userId, Long broadcastId) {
        List<PersistentUserMessageInfo> messages = userMessagesRegion.get(userId);
        if (messages != null) {
            messages.removeIf(msg -> msg.getBroadcastId().equals(broadcastId));
            if (messages.isEmpty()) {
                userMessagesRegion.remove(userId);
            } else {
                userMessagesRegion.put(userId, messages);
            }
        }
    }

    @Override
    public void cachePendingEvent(MessageDeliveryEvent event) {
        String key = event.getUserId();
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
    public Map<String, UserConnectionInfo> getConnectionsForUser(String userId) {
        return getConnectionInfoByUserId(userId)
                .map(info -> Map.of(info.getConnectionId(), info))
                .orElse(Collections.emptyMap());
    }
    
    @Override
    public long getTotalActiveUsers() {
        return userConnectionsRegion.size(); // Use userConnectionsRegion for total count
    }

    @Override
    public long getPodActiveUsers(String podId) {
        return Optional.ofNullable(podConnectionsRegion.get(podId))
                       .map(Set::size)
                       .orElse(0);
    }

    @Override
    public List<String> getOnlineUsers() {
        return new ArrayList<>(userConnectionsRegion.keySet());
    }

    @Override
    public void cacheUserMessages(String userId, List<PersistentUserMessageInfo> messages) {
        userMessagesRegion.put(userId, messages);
    }

    @Override
    public List<PersistentUserMessageInfo> getCachedUserMessages(String userId) {
        return userMessagesRegion.get(userId);
    }

    @Override
    public List<MessageDeliveryEvent> getPendingEvents(String userId) {
        return pendingEventsRegion.getOrDefault(userId, Collections.emptyList());
    }

    @Override
    public void clearPendingEvents(String userId) {
        pendingEventsRegion.remove(userId);
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

    @Override
    public List<BroadcastMessage> getActiveGroupBroadcasts(String cacheKey) {
        return activeGroupBroadcastsRegion.get(cacheKey);
    }

    @Override
    public void cacheActiveGroupBroadcasts(String cacheKey, List<BroadcastMessage> broadcasts) {
        activeGroupBroadcastsRegion.put(cacheKey, broadcasts);
    }
   
    private Optional<UserConnectionInfo> getConnectionInfoByUserId(String userId) {
        String infoJson = userConnectionsRegion.get(userId);
        if (infoJson == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(infoJson, UserConnectionInfo.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize UserConnectionInfo for userId {}", userId, e);
            return Optional.empty();
        }
    }
}