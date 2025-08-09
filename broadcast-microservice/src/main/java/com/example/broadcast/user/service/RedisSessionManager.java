package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.cache.UserSessionInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RedisSessionManager implements DistributedSessionManager {

    private final RedisTemplate<String, UserSessionInfo> userSessionRedisTemplate;
    private final RedisTemplate<String, String> stringRedisTemplate;

    private static final String SESSIONS_BY_HEARTBEAT_KEY = "active-sessions-by-heartbeat";
    private static final String POD_SESSIONS_KEY_PREFIX = "pod-sessions:";
    private static final String USER_SESSION_KEY_PREFIX = "user-sess:";

    @Override
    public void registerSession(String userId, String sessionId, String podId) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        UserSessionInfo sessionInfo = new UserSessionInfo(userId, sessionId, podId, now);
        String sessionKey = USER_SESSION_KEY_PREFIX + sessionId;

        userSessionRedisTemplate.opsForValue().set(sessionKey, sessionInfo, 1, TimeUnit.DAYS);
        stringRedisTemplate.opsForZSet().add(SESSIONS_BY_HEARTBEAT_KEY, sessionId, now.toEpochSecond());
        stringRedisTemplate.opsForSet().add(POD_SESSIONS_KEY_PREFIX + podId, sessionId);
    }

    @Override
    public void removeSession(String userId, String sessionId, String podId) {
        userSessionRedisTemplate.delete(USER_SESSION_KEY_PREFIX + sessionId);
        stringRedisTemplate.opsForZSet().remove(SESSIONS_BY_HEARTBEAT_KEY, sessionId);
        stringRedisTemplate.opsForSet().remove(POD_SESSIONS_KEY_PREFIX + podId, sessionId);
    }

    @Override
    public void updateHeartbeats(String podId, Set<String> sessionIds) {
        long now = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        for (String sessionId : sessionIds) {
            stringRedisTemplate.opsForZSet().add(SESSIONS_BY_HEARTBEAT_KEY, sessionId, now);
            UserSessionInfo session = userSessionRedisTemplate.opsForValue().get(USER_SESSION_KEY_PREFIX + sessionId);
            if (session != null) {
                UserSessionInfo updatedSession = new UserSessionInfo(session.getUserId(), session.getSessionId(), session.getPodId(), ZonedDateTime.now(ZoneOffset.UTC));
                userSessionRedisTemplate.opsForValue().set(USER_SESSION_KEY_PREFIX + sessionId, updatedSession, 1, TimeUnit.DAYS);
            }
        }
    }

    @Override
    public Set<String> getStaleSessionIds(long thresholdTimestamp) {
        Set<String> results = stringRedisTemplate.opsForZSet().rangeByScore(SESSIONS_BY_HEARTBEAT_KEY, 0, thresholdTimestamp);
        return results != null ? results : Set.of();
    }

    @Override
    public Optional<UserSessionInfo> getSessionDetails(String sessionId) {
        return Optional.ofNullable(userSessionRedisTemplate.opsForValue().get(USER_SESSION_KEY_PREFIX + sessionId));
    }

    @Override
    public void removeSessions(Set<String> sessionIds) {
        Set<String> sessionKeys = sessionIds.stream().map(id -> USER_SESSION_KEY_PREFIX + id).collect(Collectors.toSet());
        if (!sessionKeys.isEmpty()) {
            userSessionRedisTemplate.delete(sessionKeys);
        }
        if (!sessionIds.isEmpty()) {
            stringRedisTemplate.opsForZSet().remove(SESSIONS_BY_HEARTBEAT_KEY, sessionIds.toArray(new Object[0]));
        }
    }
    
    @Override
    public void markSessionsInactive(List<String> sessionIds) {
        removeSessions(Set.copyOf(sessionIds));
    }

    @Override
    public long getTotalActiveUsers() {
        Long count = stringRedisTemplate.opsForZSet().zCard(SESSIONS_BY_HEARTBEAT_KEY);
        return count != null ? count : 0;
    }

    @Override
    public long getPodActiveUsers(String podId) {
        Long count = stringRedisTemplate.opsForSet().size(POD_SESSIONS_KEY_PREFIX + podId);
        return count != null ? count : 0;
    }
}