package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.core.env.Environment;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class RedisCacheService implements CacheService{

    private static final String USER_CONNECTION_KEY_PREFIX = "user-conn:";
    private static final String CONN_TO_USER_KEY_PREFIX = "conn-to-user:";
    private static final String POD_CONNECTIONS_KEY_PREFIX = "pod-connections:";
    private static final String HEARTBEAT_KEY = "active-connections-by-heartbeat";
    private static final String ONLINE_USERS_KEY = "online-users";
    private static final String USER_MESSAGES_KEY_PREFIX = "user-msg:";
    private static final String PENDING_EVENTS_KEY_PREFIX = "pending-evt:";
    private static final String BROADCAST_CONTENT_KEY_PREFIX = "broadcast-content:";
    private static final String ACTIVE_GROUP_BROADCASTS_KEY_PREFIX = "active-group-bcast:";

    private static final long CONNECTION_TTL_MINUTES = 30;

    private final RedisConnectionFactory redisConnectionFactory;
    private final RedisTemplate<String, String> stringRedisTemplate;
    private final RedisTemplate<String, Object> genericRedisTemplate;
    private final RedisTemplate<String, List<PersistentUserMessageInfo>> persistentUserMessagesRedisTemplate;
    private final RedisTemplate<String, List<PendingEventInfo>> pendingEventsRedisTemplate;
    private final RedisTemplate<String, BroadcastMessage> broadcastMessageRedisTemplate;
    private final RedisTemplate<String, List<BroadcastMessage>> activeGroupBroadcastsRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisCacheService(RedisConnectionFactory redisConnectionFactory,
                             RedisTemplate<String, String> stringRedisTemplate,
                             RedisTemplate<String, Object> genericRedisTemplate,
                             RedisTemplate<String, List<PersistentUserMessageInfo>> persistentUserMessagesRedisTemplate,
                             RedisTemplate<String, List<PendingEventInfo>> pendingEventsRedisTemplate,
                             RedisTemplate<String, BroadcastMessage> broadcastMessageRedisTemplate,
                             RedisTemplate<String, List<BroadcastMessage>> activeGroupBroadcastsRedisTemplate,
                             ObjectMapper objectMapper, Environment environment) {
        this.redisConnectionFactory = redisConnectionFactory;
        this.stringRedisTemplate = stringRedisTemplate;
        this.genericRedisTemplate = genericRedisTemplate;
        this.persistentUserMessagesRedisTemplate = persistentUserMessagesRedisTemplate;
        this.pendingEventsRedisTemplate = pendingEventsRedisTemplate;
        this.broadcastMessageRedisTemplate = broadcastMessageRedisTemplate;
        this.activeGroupBroadcastsRedisTemplate = activeGroupBroadcastsRedisTemplate;
        this.objectMapper = objectMapper;

        // Register a JVM shutdown hook to ensure cache is cleared
        // Only register the shutdown hook if the "admin-only" profile is active
        if (environment.matchesProfiles("admin-only")) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::cleanupCacheOnShutdown));
        }
    }

    private void cleanupCacheOnShutdown() {
        log.info("JVM Shutdown Hook: flushing the entire Redis database...");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
            log.info("Redis database flushed successfully via shutdown hook.");
        } catch (Exception e) {
            // Log as a warning since the app is shutting down anyway
            log.warn("Error during Redis DB flush via shutdown hook: {}", e.getMessage());
        }
    }

    @Override
    public void registerUserConnection(String userId, String connectionId, String podId, String clusterName) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        UserConnectionInfo connectionInfo = new UserConnectionInfo(userId, connectionId, podId, clusterName, now, now);

        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        String reverseLookupKey = CONN_TO_USER_KEY_PREFIX + connectionId;
        String podConnectionsKey = POD_CONNECTIONS_KEY_PREFIX + podId;

        try {
            String infoJson = objectMapper.writeValueAsString(connectionInfo);
            genericRedisTemplate.opsForHash().put(userKey, connectionId, infoJson);
            genericRedisTemplate.expire(userKey, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
            stringRedisTemplate.opsForValue().set(reverseLookupKey, userId, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
            stringRedisTemplate.opsForZSet().add(HEARTBEAT_KEY, connectionId, now.toEpochSecond());
            stringRedisTemplate.opsForSet().add(ONLINE_USERS_KEY, userId);
            stringRedisTemplate.opsForSet().add(podConnectionsKey, connectionId);
            log.debug("User connection registered in Redis: userId={}, connectionId={}", userId, connectionId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize UserConnectionInfo for userId {}", userId, e);
        }
    }
    
    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
        Optional<UserConnectionInfo> details = getConnectionDetails(connectionId);
        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        String reverseLookupKey = CONN_TO_USER_KEY_PREFIX + connectionId;
        genericRedisTemplate.opsForHash().delete(userKey, connectionId);
        stringRedisTemplate.delete(reverseLookupKey);
        stringRedisTemplate.opsForZSet().remove(HEARTBEAT_KEY, connectionId);
        details.ifPresent(info -> stringRedisTemplate.opsForSet().remove(POD_CONNECTIONS_KEY_PREFIX + info.getPodId(), connectionId));
        Long remainingConnections = genericRedisTemplate.opsForHash().size(userKey);
        if (remainingConnections == null || remainingConnections == 0) {
            stringRedisTemplate.opsForSet().remove(ONLINE_USERS_KEY, userId);
        }
        log.debug("User connection unregistered from Redis: userId={}, connectionId={}", userId, connectionId);
    }

    @Override
    public long getTotalActiveUsers() {
        Long count = stringRedisTemplate.opsForZSet().zCard(HEARTBEAT_KEY);
        return count != null ? count : 0;
    }

    @Override
    public long getPodActiveUsers(String podId) {
        Long count = stringRedisTemplate.opsForSet().size(POD_CONNECTIONS_KEY_PREFIX + podId);
        return count != null ? count : 0;
    }

    @Override
    public Map<String, UserConnectionInfo> getConnectionsForUser(String userId) {
        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        HashOperations<String, String, String> hashOps = genericRedisTemplate.opsForHash();
        Map<String, String> entries = hashOps.entries(userKey);

        return entries.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                    try {
                        return objectMapper.readValue(entry.getValue(), UserConnectionInfo.class);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to deserialize UserConnectionInfo for userId {} connId {}", userId, entry.getKey(), e);
                        return null;
                    }
                }))
                .entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public void updateHeartbeats(Set<String> connectionIds) {
        long nowEpoch = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        for (String connectionId : connectionIds) {
            stringRedisTemplate.opsForZSet().add(HEARTBEAT_KEY, connectionId, nowEpoch);
            stringRedisTemplate.expire(CONN_TO_USER_KEY_PREFIX + connectionId, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
            String userId = stringRedisTemplate.opsForValue().get(CONN_TO_USER_KEY_PREFIX + connectionId);
            if (userId != null) {
                genericRedisTemplate.expire(USER_CONNECTION_KEY_PREFIX + userId, CONNECTION_TTL_MINUTES, TimeUnit.MINUTES);
            }
        }
    }

    @Override
    public Set<String> getStaleConnectionIds(long thresholdTimestamp) {
        Set<String> staleIds = stringRedisTemplate.opsForZSet().rangeByScore(HEARTBEAT_KEY, 0, thresholdTimestamp);
        return staleIds != null ? staleIds : Collections.emptySet();
    }

    @Override
    public Optional<UserConnectionInfo> getConnectionDetails(String connectionId) {
        String userId = stringRedisTemplate.opsForValue().get(CONN_TO_USER_KEY_PREFIX + connectionId);
        if (userId == null) {
            return Optional.empty();
        }
        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        String infoJson = (String) genericRedisTemplate.opsForHash().get(userKey, connectionId);

        if (infoJson == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(infoJson, UserConnectionInfo.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize UserConnectionInfo for connId {}", connectionId, e);
            return Optional.empty();
        }
    }

    @Override
    public void removeConnections(Set<String> connectionIds) {
        if (connectionIds == null || connectionIds.isEmpty()) return;

        for (String connectionId : connectionIds) {
            String userId = stringRedisTemplate.opsForValue().get(CONN_TO_USER_KEY_PREFIX + connectionId);
            if (userId != null) {
                unregisterUserConnection(userId, connectionId);
            } else {
                stringRedisTemplate.opsForZSet().remove(HEARTBEAT_KEY, connectionId);
            }
        }
    }

    @Override
    public boolean isUserOnline(String userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(ONLINE_USERS_KEY, userId));
    }

    @Override
    public List<String> getOnlineUsers() {
        Set<String> members = stringRedisTemplate.opsForSet().members(ONLINE_USERS_KEY);
        return members != null ? new ArrayList<>(members) : Collections.emptyList();
    }

    @Override
    public void cacheUserMessages(String userId, List<PersistentUserMessageInfo> messages) {
        persistentUserMessagesRedisTemplate.opsForValue().set(USER_MESSAGES_KEY_PREFIX + userId, messages, 24, TimeUnit.HOURS);
    }

    @Override
    public List<PersistentUserMessageInfo> getCachedUserMessages(String userId) {
        return persistentUserMessagesRedisTemplate.opsForValue().get(USER_MESSAGES_KEY_PREFIX + userId);
    }

    @Override
    public void addMessageToUserCache(String userId, PersistentUserMessageInfo message) {
        String key = USER_MESSAGES_KEY_PREFIX + userId;
        List<PersistentUserMessageInfo> messages = persistentUserMessagesRedisTemplate.opsForValue().get(key);
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(0, message);
        persistentUserMessagesRedisTemplate.opsForValue().set(key, messages, 24, TimeUnit.HOURS);
    }

    @Override
    public void removeMessageFromUserCache(String userId, Long broadcastId) {
        String key = USER_MESSAGES_KEY_PREFIX + userId;
        List<PersistentUserMessageInfo> messages = persistentUserMessagesRedisTemplate.opsForValue().get(key);
        if (messages != null) {
            messages.removeIf(msg -> msg.getBroadcastId().equals(broadcastId));
            persistentUserMessagesRedisTemplate.opsForValue().set(key, messages, 24, TimeUnit.HOURS);
        }
    }

    @Override
    public void cachePendingEvent(MessageDeliveryEvent event) {
        String key = PENDING_EVENTS_KEY_PREFIX + event.getUserId();
        PendingEventInfo pendingEvent = new PendingEventInfo(event.getEventId(), event.getBroadcastId(), event.getEventType(), event.getTimestamp(), event.getMessage());
        List<PendingEventInfo> pendingEvents = pendingEventsRedisTemplate.opsForValue().get(key);
        if (pendingEvents == null) {
            pendingEvents = new ArrayList<>();
        }
        pendingEvents.add(pendingEvent);
        pendingEventsRedisTemplate.opsForValue().set(key, pendingEvents, 6, TimeUnit.HOURS);
    }

    @Override
    public List<MessageDeliveryEvent> getPendingEvents(String userId) {
        List<PendingEventInfo> pendingEvents = pendingEventsRedisTemplate.opsForValue().get(PENDING_EVENTS_KEY_PREFIX + userId);
        if (pendingEvents == null) return List.of();

        return pendingEvents.stream()
                .map(p -> new MessageDeliveryEvent(p.getEventId(), p.getBroadcastId(), userId, p.getEventType(), null, p.getTimestamp(), p.getMessage(), null, false))
                .collect(Collectors.toList());
    }

    @Override
    public void removePendingEvent(String userId, Long broadcastId) {
        String key = PENDING_EVENTS_KEY_PREFIX + userId;
        List<PendingEventInfo> pendingEvents = pendingEventsRedisTemplate.opsForValue().get(key);
        if (pendingEvents != null) {
            pendingEvents.removeIf(event -> event.getBroadcastId().equals(broadcastId));
            pendingEventsRedisTemplate.opsForValue().set(key, pendingEvents, 6, TimeUnit.HOURS);
        }
    }

    @Override
    public void clearPendingEvents(String userId) {
        pendingEventsRedisTemplate.delete(PENDING_EVENTS_KEY_PREFIX + userId);
    }

    @Override
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            Properties info = connection.serverCommands().info("memory");
            if (info != null) {
                stats.put("usedMemory", info.getProperty("used_memory_human"));
                stats.put("peakMemory", info.getProperty("used_memory_peak_human"));
                stats.put("fragmentationRatio", info.getProperty("mem_fragmentation_ratio"));
            }
            stats.put("totalKeys", connection.serverCommands().dbSize());
            Map<String, Long> keyCounts = new LinkedHashMap<>();
            keyCounts.put("userConnectionHashes", countKeysByPattern(connection, USER_CONNECTION_KEY_PREFIX + "*"));
            keyCounts.put("userMessages", countKeysByPattern(connection, USER_MESSAGES_KEY_PREFIX + "*"));
            keyCounts.put("pendingEvents", countKeysByPattern(connection, PENDING_EVENTS_KEY_PREFIX + "*"));
            keyCounts.put("broadcastContent", countKeysByPattern(connection, BROADCAST_CONTENT_KEY_PREFIX + "*"));
            keyCounts.put("onlineUsersSetSize", Optional.ofNullable(connection.setCommands().sCard(ONLINE_USERS_KEY.getBytes())).orElse(0L));
            stats.put("keyCountsByPrefix", keyCounts);
        } catch (Exception e) {
            log.error("Failed to get Redis cache stats", e);
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    private long countKeysByPattern(RedisConnection connection, String pattern) {
        long count = 0;
        try (Cursor<byte[]> cursor = connection.keyCommands().scan(ScanOptions.scanOptions().match(pattern).count(1000).build())) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        } catch (Exception e) {
            log.error("Could not scan keys for pattern '{}'", pattern, e);
        }
        return count;
    }

    @Override
    public Optional<BroadcastMessage> getBroadcastContent(Long broadcastId) {
        String key = BROADCAST_CONTENT_KEY_PREFIX + broadcastId;
        return Optional.ofNullable(broadcastMessageRedisTemplate.opsForValue().get(key));
    }

    @Override
    public void cacheBroadcastContent(BroadcastMessage broadcast) {
        if (broadcast != null && broadcast.getId() != null) {
            String key = BROADCAST_CONTENT_KEY_PREFIX + broadcast.getId();
            broadcastMessageRedisTemplate.opsForValue().set(key, broadcast, 1, TimeUnit.HOURS);
        }
    }

    @Override
    public void evictBroadcastContent(Long broadcastId) {
        if (broadcastId != null) {
            String key = BROADCAST_CONTENT_KEY_PREFIX + broadcastId;
            broadcastMessageRedisTemplate.delete(key);
        }
    }

    @Override
    public List<BroadcastMessage> getActiveGroupBroadcasts(String cacheKey) {
        String redisKey = ACTIVE_GROUP_BROADCASTS_KEY_PREFIX + cacheKey;
        return activeGroupBroadcastsRedisTemplate.opsForValue().get(redisKey);
    }

    @Override
    public void cacheActiveGroupBroadcasts(String cacheKey, List<BroadcastMessage> broadcasts) {
        String redisKey = ACTIVE_GROUP_BROADCASTS_KEY_PREFIX + cacheKey;
        activeGroupBroadcastsRedisTemplate.opsForValue().set(redisKey, broadcasts, 60, TimeUnit.SECONDS);
    }

    @Override
    public void evictActiveGroupBroadcastsCache() {
        log.warn("Evicting active group broadcasts cache via SCAN operation.");
        try (RedisConnection connection = redisConnectionFactory.getConnection()) {
            ScanOptions options = ScanOptions.scanOptions().match(ACTIVE_GROUP_BROADCASTS_KEY_PREFIX + "*").count(100).build();
            try (Cursor<byte[]> cursor = connection.keyCommands().scan(options)) {
                List<byte[]> keysToDelete = new ArrayList<>();
                while (cursor.hasNext()) {
                    keysToDelete.add(cursor.next());
                }
                if (!keysToDelete.isEmpty()) {
                    connection.keyCommands().del(keysToDelete.toArray(new byte[0][]));
                }
            }
        } catch (Exception e) {
            log.error("Error during Redis SCAN+DEL for active group broadcasts cache eviction.", e);
        }
    }
}