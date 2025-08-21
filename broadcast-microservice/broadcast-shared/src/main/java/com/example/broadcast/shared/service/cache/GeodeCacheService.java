package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.PersistentUserMessageInfo;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ObjectMapper objectMapper;
    private final ClientCache clientCache;
    private final Region<String, String> userConnectionsRegion;
    private final Region<String, String> connectionToUserRegion;
    private final Region<String, Boolean> onlineUsersRegion;
    private final Region<String, Set<String>> podConnectionsRegion;
    private final Region<String, Long> heartbeatRegion;
    private final Region<String, List<MessageDeliveryEvent>> pendingEventsRegion;
    private final Region<Long, BroadcastMessage> broadcastContentRegion;
    private final Region<String, List<BroadcastMessage>> activeGroupBroadcastsRegion;
    private final Region<String, List<PersistentUserMessageInfo>> userMessagesRegion;

    public GeodeCacheService(ObjectMapper objectMapper,
                             ClientCache clientCache,
                             @Qualifier("userConnectionsRegion") Region<String, String> userConnectionsRegion,
                             @Qualifier("connectionToUserRegion") Region<String, String> connectionToUserRegion,
                             @Qualifier("onlineUsersRegion") Region<String, Boolean> onlineUsersRegion,
                             @Qualifier("podConnectionsRegion") Region<String, Set<String>> podConnectionsRegion,
                             @Qualifier("heartbeatRegion") Region<String, Long> heartbeatRegion,
                             @Qualifier("pendingEventsRegion") Region<String, List<MessageDeliveryEvent>> pendingEventsRegion,
                             @Qualifier("broadcastContentRegion") Region<Long, BroadcastMessage> broadcastContentRegion,
                             @Qualifier("activeGroupBroadcastsRegion") Region<String, List<BroadcastMessage>> activeGroupBroadcastsRegion,
                             @Qualifier("userMessagesRegion") Region<String, List<PersistentUserMessageInfo>> userMessagesRegion
    ) {
        this.objectMapper = objectMapper;
        this.clientCache = clientCache;
        this.userConnectionsRegion = userConnectionsRegion;
        this.connectionToUserRegion = connectionToUserRegion;
        this.onlineUsersRegion = onlineUsersRegion;
        this.podConnectionsRegion = podConnectionsRegion;
        this.heartbeatRegion = heartbeatRegion;
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
            connectionToUserRegion.put(connectionId, userId);
            onlineUsersRegion.put(userId, true);

            // --- REFACTORED to remove .compute() ---
            Set<String> podConnections = podConnectionsRegion.get(podId);
            if (podConnections == null) {
                podConnections = new HashSet<>();
            }
            podConnections.add(connectionId);
            podConnectionsRegion.put(podId, podConnections);
            // --- END REFACTOR ---

            heartbeatRegion.put(connectionId, now.toEpochSecond());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize UserConnectionInfo", e);
        }
    }

    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
        getConnectionInfo(connectionId).ifPresent(info -> {
            String podId = info.getPodId();
            // --- REFACTORED to remove .computeIfPresent() ---
            Set<String> podConnections = podConnectionsRegion.get(podId);
            if (podConnections != null) {
                podConnections.remove(connectionId);
                if (podConnections.isEmpty()) {
                    podConnectionsRegion.remove(podId);
                } else {
                    podConnectionsRegion.put(podId, podConnections);
                }
            }
            // --- END REFACTOR ---
        });
        userConnectionsRegion.remove(userId);
        connectionToUserRegion.remove(connectionId);
        onlineUsersRegion.remove(userId);
        heartbeatRegion.remove(connectionId);
    }

    @Override
    public void addMessageToUserCache(String userId, PersistentUserMessageInfo message) {
        // --- REFACTORED to remove .compute() ---
        List<PersistentUserMessageInfo> messages = userMessagesRegion.get(userId);
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(0, message);
        userMessagesRegion.put(userId, messages);
        // --- END REFACTOR ---
    }

    @Override
    public void removeMessageFromUserCache(String userId, Long broadcastId) {
        // --- REFACTORED to remove .computeIfPresent() ---
        List<PersistentUserMessageInfo> messages = userMessagesRegion.get(userId);
        if (messages != null) {
            messages.removeIf(msg -> msg.getBroadcastId().equals(broadcastId));
            if (messages.isEmpty()) {
                userMessagesRegion.remove(userId);
            } else {
                userMessagesRegion.put(userId, messages);
            }
        }
        // --- END REFACTOR ---
    }

    @Override
    public void cachePendingEvent(MessageDeliveryEvent event) {
        // --- REFACTORED to remove .compute() ---
        String key = event.getUserId();
        List<MessageDeliveryEvent> pendingEvents = pendingEventsRegion.get(key);
        if (pendingEvents == null) {
            pendingEvents = new ArrayList<>();
        }
        pendingEvents.add(event);
        pendingEventsRegion.put(key, pendingEvents);
        // --- END REFACTOR ---
    }

    @Override
    public void removePendingEvent(String userId, Long broadcastId) {
        // --- REFACTORED to remove .computeIfPresent() ---
        List<MessageDeliveryEvent> pendingEvents = pendingEventsRegion.get(userId);
        if (pendingEvents != null) {
            pendingEvents.removeIf(evt -> evt.getBroadcastId().equals(broadcastId));
            if (pendingEvents.isEmpty()) {
                pendingEventsRegion.remove(userId);
            } else {
                pendingEventsRegion.put(userId, pendingEvents);
            }
        }
        // --- END REFACTOR ---
    }

    // --- Methods below this line are unchanged ---

    @Override
    public Map<String, UserConnectionInfo> getConnectionsForUser(String userId) {
        return getConnectionInfoByUserId(userId)
                .map(info -> Map.of(info.getConnectionId(), info))
                .orElse(Collections.emptyMap());
    }

    @Override
    public boolean isUserOnline(String userId) {
        return onlineUsersRegion.containsKey(userId);
    }

    @Override
    public void updateHeartbeats(Set<String> connectionIds) {
        long now = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        Map<String, Long> updates = connectionIds.stream().collect(Collectors.toMap(id -> id, id -> now));
        heartbeatRegion.putAll(updates);
    }

    @Override
    public Set<String> getStaleConnectionIds(long thresholdTimestamp) {
        return heartbeatRegion.entrySet().stream()
                .filter(entry -> entry.getValue() < thresholdTimestamp)
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
            String userId = connectionToUserRegion.get(connId);
            if (userId != null) {
                unregisterUserConnection(userId, connId);
            }
        });
    }

    @Override
    public long getTotalActiveUsers() {
        return onlineUsersRegion.size();
    }

    @Override
    public long getPodActiveUsers(String podId) {
        return Optional.ofNullable(podConnectionsRegion.get(podId))
                       .map(Set::size)
                       .orElse(0);
    }

    @Override
    public List<String> getOnlineUsers() {
        return new ArrayList<>(onlineUsersRegion.keySet());
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
        stats.put("totalTrackedConnections", heartbeatRegion.size());
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

    @Override
    public void evictActiveGroupBroadcastsCache() {
        Set<String> keysToClear = activeGroupBroadcastsRegion.keySet();
        keysToClear.forEach(activeGroupBroadcastsRegion::remove);
        log.warn("Cleared all entries from activeGroupBroadcastsRegion from client-side.");
    }

    // --- Private Helper Methods ---

    private Optional<UserConnectionInfo> getConnectionInfo(String connectionId) {
        String userId = connectionToUserRegion.get(connectionId);
        if (userId == null) {
            return Optional.empty();
        }
        return getConnectionInfoByUserId(userId);
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