package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisCacheService implements CacheService {

    private final RedisConnectionFactory redisConnectionFactory;

    private final RedisTemplate<String, String> stringRedisTemplate;
    private final RedisTemplate<String, UserConnectionInfo> userConnectionInfoRedisTemplate;
    private final RedisTemplate<String, List<PersistentUserMessageInfo>> persistentUserMessagesRedisTemplate;
    private final RedisTemplate<String, List<PendingEventInfo>> pendingEventsRedisTemplate;
    private final RedisTemplate<String, BroadcastMessage> broadcastMessageRedisTemplate;
    private final RedisTemplate<String, List<BroadcastMessage>> activeGroupBroadcastsRedisTemplate;

    private static final String USER_CONNECTION_KEY_PREFIX = "user-conn:";
    private static final String ONLINE_USERS_KEY = "online-users";
    private static final String USER_MESSAGES_KEY_PREFIX = "user-msg:";
    private static final String PENDING_EVENTS_KEY_PREFIX = "pending-evt:";
    private static final String BROADCAST_CONTENT_KEY_PREFIX = "broadcast-content:";
    private static final String ACTIVE_GROUP_BROADCASTS_KEY_PREFIX = "active-group-bcast:";

    @Override
    public void registerUserConnection(String userId, String connectionId, String podId) {
        // MERGED: A single object is now created and stored.
        UserConnectionInfo connectionInfo = new UserConnectionInfo(userId, connectionId, podId, ZonedDateTime.now(), ZonedDateTime.now());
        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        // The TTL is now handled by the DistributedConnectionManager's configuration
        userConnectionInfoRedisTemplate.opsForValue().set(userKey, connectionInfo, 30, TimeUnit.MINUTES);
        stringRedisTemplate.opsForSet().add(ONLINE_USERS_KEY, userId);
        // REMOVED: Logic for creating and storing a separate UserSessionInfo is gone.
        log.debug("User connection registered in Redis: {} on pod {}", userId, podId);
    }

    @Override
    public void unregisterUserConnection(String userId, String connectionId) {
        userConnectionInfoRedisTemplate.delete(USER_CONNECTION_KEY_PREFIX + userId);
        stringRedisTemplate.opsForSet().remove(ONLINE_USERS_KEY, userId);
        // REMOVED: Logic for deleting a separate UserSessionInfo is gone.
        log.debug("User connection unregistered from Redis: {}", userId);
    }

    @Override
    public void updateUserActivity(String userId) {
        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        UserConnectionInfo connectionInfo = userConnectionInfoRedisTemplate.opsForValue().get(userKey);
        if (connectionInfo != null) {
            UserConnectionInfo updatedInfo = new UserConnectionInfo(
                    connectionInfo.getUserId(), connectionInfo.getConnectionId(), connectionInfo.getPodId(),
                    connectionInfo.getConnectedAt(), ZonedDateTime.now()
            );
            // MERGED: Updating this one key now refreshes the sliding TTL automatically.
            userConnectionInfoRedisTemplate.opsForValue().set(userKey, updatedInfo, 30, TimeUnit.MINUTES);
            // REMOVED: Logic for updating a separate UserSessionInfo is gone.
        }
    }

    @Override
    public boolean isUserOnline(String userId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(ONLINE_USERS_KEY, userId));
    }

    @Override
    public UserConnectionInfo getUserConnectionInfo(String userId) {
        return userConnectionInfoRedisTemplate.opsForValue().get(USER_CONNECTION_KEY_PREFIX + userId);
    }

    @Override
    public List<String> getOnlineUsers() {
        return new ArrayList<>(Objects.requireNonNull(stringRedisTemplate.opsForSet().members(ONLINE_USERS_KEY)));
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
                .map(p -> new MessageDeliveryEvent(p.getEventId(), p.getBroadcastId(), userId, p.getEventType(), null, p.getTimestamp(), p.getMessage(), null,false))
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
            keyCounts.put("userConnections", countKeysByPattern(connection, USER_CONNECTION_KEY_PREFIX + "*"));
            keyCounts.put("userMessages", countKeysByPattern(connection, USER_MESSAGES_KEY_PREFIX + "*"));
            keyCounts.put("pendingEvents", countKeysByPattern(connection, PENDING_EVENTS_KEY_PREFIX + "*"));
            keyCounts.put("broadcastContent", countKeysByPattern(connection, BROADCAST_CONTENT_KEY_PREFIX + "*"));
            keyCounts.put("onlineUsersSetSize", connection.setCommands().sCard(ONLINE_USERS_KEY.getBytes()));

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
                while (cursor.hasNext()) {
                    connection.keyCommands().del(cursor.next());
                }
            }
        } catch (Exception e) {
            log.error("Error during Redis SCAN+DEL for active group broadcasts cache eviction.", e);
        }
    }
}