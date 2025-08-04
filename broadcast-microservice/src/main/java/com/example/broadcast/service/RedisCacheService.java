package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.dto.cache.*;
import com.example.broadcast.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Profile("redis")
@RequiredArgsConstructor
@Slf4j
public class RedisCacheService implements CacheService {

    private final RedisTemplate<String, String> stringRedisTemplate;
    private final RedisTemplate<String, UserConnectionInfo> userConnectionInfoRedisTemplate;
    private final RedisTemplate<String, List<UserMessageInfo>> userMessagesRedisTemplate;
    private final RedisTemplate<String, List<PendingEventInfo>> pendingEventsRedisTemplate;
    private final RedisTemplate<String, UserSessionInfo> userSessionRedisTemplate;
    private final RedisTemplate<String, BroadcastStatsInfo> broadcastStatsRedisTemplate;

    // ... (key prefixes and methods before getPendingEvents are unchanged)
    private static final String USER_CONNECTION_KEY_PREFIX = "user-conn:";
    private static final String ONLINE_USERS_KEY = "online-users";
    private static final String USER_MESSAGES_KEY_PREFIX = "user-msg:";
    private static final String PENDING_EVENTS_KEY_PREFIX = "pending-evt:";
    private static final String BROADCAST_STATS_KEY_PREFIX = "broadcast-stats:";
    private static final String USER_SESSION_KEY_PREFIX = "user-sess:";

    @Override
    public void registerUserConnection(String userId, String sessionId, String podId) {
        UserConnectionInfo connectionInfo = new UserConnectionInfo(userId, sessionId, podId, ZonedDateTime.now(), ZonedDateTime.now());
        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        userConnectionInfoRedisTemplate.opsForValue().set(userKey, connectionInfo, 1, TimeUnit.HOURS);
        stringRedisTemplate.opsForSet().add(ONLINE_USERS_KEY, userId);

        UserSessionInfo sessionInfo = new UserSessionInfo(userId, sessionId, podId, ZonedDateTime.now());
        String sessionKey = USER_SESSION_KEY_PREFIX + sessionId;
        userSessionRedisTemplate.opsForValue().set(sessionKey, sessionInfo, 30, TimeUnit.MINUTES);

        log.debug("User connection and session registered in Redis: {} on pod {}", userId, podId);
    }

    @Override
    public void unregisterUserConnection(String userId, String sessionId) {
        userConnectionInfoRedisTemplate.delete(USER_CONNECTION_KEY_PREFIX + userId);
        stringRedisTemplate.opsForSet().remove(ONLINE_USERS_KEY, userId);
        userSessionRedisTemplate.delete(USER_SESSION_KEY_PREFIX + sessionId);
        log.debug("User connection and session unregistered from Redis: {}", userId);
    }

    @Override
    public void updateUserActivity(String userId) {
        String userKey = USER_CONNECTION_KEY_PREFIX + userId;
        UserConnectionInfo connectionInfo = userConnectionInfoRedisTemplate.opsForValue().get(userKey);
        if (connectionInfo != null) {
            UserConnectionInfo updatedInfo = new UserConnectionInfo(
                    connectionInfo.getUserId(), connectionInfo.getSessionId(), connectionInfo.getPodId(),
                    connectionInfo.getConnectedAt(), ZonedDateTime.now()
            );
            userConnectionInfoRedisTemplate.opsForValue().set(userKey, updatedInfo, 1, TimeUnit.HOURS);
            
            String sessionKey = USER_SESSION_KEY_PREFIX + connectionInfo.getSessionId();
            UserSessionInfo sessionInfo = userSessionRedisTemplate.opsForValue().get(sessionKey);
            if (sessionInfo != null) {
                UserSessionInfo updatedSession = new UserSessionInfo(userId, sessionInfo.getSessionId(), sessionInfo.getPodId(), ZonedDateTime.now());
                userSessionRedisTemplate.opsForValue().set(sessionKey, updatedSession, 30, TimeUnit.MINUTES);
            }
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
    public void cacheUserMessages(String userId, List<UserMessageInfo> messages) {
        userMessagesRedisTemplate.opsForValue().set(USER_MESSAGES_KEY_PREFIX + userId, messages, 24, TimeUnit.HOURS);
    }

    @Override
    public List<UserMessageInfo> getCachedUserMessages(String userId) {
        return userMessagesRedisTemplate.opsForValue().get(USER_MESSAGES_KEY_PREFIX + userId);
    }

    @Override
    public void addMessageToUserCache(String userId, UserMessageInfo message) {
        String key = USER_MESSAGES_KEY_PREFIX + userId;
        List<UserMessageInfo> messages = userMessagesRedisTemplate.opsForValue().get(key);
        if (messages == null) {
            messages = new ArrayList<>();
        }
        messages.add(0, message); // Add to the beginning
        userMessagesRedisTemplate.opsForValue().set(key, messages, 24, TimeUnit.HOURS);
    }

    @Override
    public void removeMessageFromUserCache(String userId, Long messageId) {
        String key = USER_MESSAGES_KEY_PREFIX + userId;
        List<UserMessageInfo> messages = userMessagesRedisTemplate.opsForValue().get(key);
        if (messages != null) {
            messages.removeIf(msg -> msg.getMessageId().equals(messageId));
            userMessagesRedisTemplate.opsForValue().set(key, messages, 24, TimeUnit.HOURS);
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

        // START OF FIX: Added the 9th argument (false) to the constructor call.
        return pendingEvents.stream()
                .map(p -> new MessageDeliveryEvent(p.getEventId(), p.getBroadcastId(), userId, p.getEventType(), null, p.getTimestamp(), p.getMessage(), null, false))
                .collect(Collectors.toList());
        // END OF FIX
    }

    // ... (rest of the file is unchanged)
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
    public void updateMessageReadStatus(String userId, Long broadcastId) {
        String key = USER_MESSAGES_KEY_PREFIX + userId;
        List<UserMessageInfo> messages = userMessagesRedisTemplate.opsForValue().get(key);
        if (messages != null) {
            List<UserMessageInfo> updatedMessages = messages.stream()
                    .map(msg -> {
                        if (msg.getBroadcastId().equals(broadcastId)) {
                            return new UserMessageInfo(msg.getMessageId(), msg.getBroadcastId(), msg.getContent(), msg.getPriority(), msg.getCreatedAt(), msg.getDeliveryStatus(), Constants.ReadStatus.READ.name());
                        }
                        return msg;
                    }).collect(Collectors.toList());
            userMessagesRedisTemplate.opsForValue().set(key, updatedMessages, 24, TimeUnit.HOURS);
        }
    }

    @Override
    public void cacheBroadcastStats(String statsKey, BroadcastStatsInfo stats) {
        broadcastStatsRedisTemplate.opsForValue().set(BROADCAST_STATS_KEY_PREFIX + statsKey, stats, 5, TimeUnit.MINUTES);
    }

    @Override
    public BroadcastStatsInfo getCachedBroadcastStats(String statsKey) {
        return broadcastStatsRedisTemplate.opsForValue().get(BROADCAST_STATS_KEY_PREFIX + statsKey);
    }

    @Override
    public Map<String, Object> getCacheStats() {
        return Map.of(
                "cacheType", "Redis",
                "onlineUsersCount", Objects.requireNonNull(stringRedisTemplate.opsForSet().size(ONLINE_USERS_KEY))
        );
    }
}