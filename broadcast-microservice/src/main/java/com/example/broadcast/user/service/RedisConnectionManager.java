package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

// CHANGED: Renamed class from RedisSessionManager to RedisConnectionManager
@Service
@RequiredArgsConstructor
public class RedisConnectionManager implements DistributedConnectionManager {

    // MERGED: Only one template is needed now
    private final RedisTemplate<String, UserConnectionInfo> userConnectionInfoRedisTemplate;
    private final RedisTemplate<String, String> stringRedisTemplate;

    // CHANGED: Renamed keys for clarity
    private static final String CONNECTIONS_BY_HEARTBEAT_KEY = "active-connections-by-heartbeat";
    private static final String POD_CONNECTIONS_KEY_PREFIX = "pod-connections:";
    private static final String USER_CONNECTION_KEY_PREFIX = "user-conn:";

    @Override
    public void registerConnection(String userId, String connectionId, String podId) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        UserConnectionInfo connectionInfo = new UserConnectionInfo(userId, connectionId, podId, now, now);
        String connectionKey = USER_CONNECTION_KEY_PREFIX + connectionId;

        // Use a single Redis entry with a sliding expiration
        userConnectionInfoRedisTemplate.opsForValue().set(connectionKey, connectionInfo, 30, TimeUnit.MINUTES);
        stringRedisTemplate.opsForZSet().add(CONNECTIONS_BY_HEARTBEAT_KEY, connectionId, now.toEpochSecond());
        stringRedisTemplate.opsForSet().add(POD_CONNECTIONS_KEY_PREFIX + podId, connectionId);
    }

    @Override
    public void removeConnection(String userId, String connectionId, String podId) {
        userConnectionInfoRedisTemplate.delete(USER_CONNECTION_KEY_PREFIX + connectionId);
        stringRedisTemplate.opsForZSet().remove(CONNECTIONS_BY_HEARTBEAT_KEY, connectionId);
        stringRedisTemplate.opsForSet().remove(POD_CONNECTIONS_KEY_PREFIX + podId, connectionId);
    }

    @Override
    public void updateHeartbeats(String podId, Set<String> connectionIds) {
        long nowEpoch = ZonedDateTime.now(ZoneOffset.UTC).toEpochSecond();
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);

        for (String connectionId : connectionIds) {
            stringRedisTemplate.opsForZSet().add(CONNECTIONS_BY_HEARTBEAT_KEY, connectionId, nowEpoch);

            // Also update the main connection object's lastActivity and refresh its TTL
            String connectionKey = USER_CONNECTION_KEY_PREFIX + connectionId;
            UserConnectionInfo connection = userConnectionInfoRedisTemplate.opsForValue().get(connectionKey);
            if (connection != null) {
                UserConnectionInfo updatedConnection = new UserConnectionInfo(
                    connection.getUserId(),
                    connection.getConnectionId(),
                    connection.getPodId(),
                    connection.getConnectedAt(), // connectedAt timestamp remains the same
                    now // lastActivityAt is updated
                );
                userConnectionInfoRedisTemplate.opsForValue().set(connectionKey, updatedConnection, 30, TimeUnit.MINUTES);
            }
        }
    }

    @Override
    public Set<String> getStaleConnectionIds(long thresholdTimestamp) {
        Set<String> results = stringRedisTemplate.opsForZSet().rangeByScore(CONNECTIONS_BY_HEARTBEAT_KEY, 0, thresholdTimestamp);
        return results != null ? results : Set.of();
    }

    @Override
    public Optional<UserConnectionInfo> getConnectionDetails(String connectionId) {
        return Optional.ofNullable(userConnectionInfoRedisTemplate.opsForValue().get(USER_CONNECTION_KEY_PREFIX + connectionId));
    }

    @Override
    public void removeConnections(Set<String> connectionIds) {
        Set<String> connectionKeys = connectionIds.stream().map(id -> USER_CONNECTION_KEY_PREFIX + id).collect(Collectors.toSet());
        if (!connectionKeys.isEmpty()) {
            userConnectionInfoRedisTemplate.delete(connectionKeys);
        }
        if (!connectionIds.isEmpty()) {
            stringRedisTemplate.opsForZSet().remove(CONNECTIONS_BY_HEARTBEAT_KEY, connectionIds.toArray(new Object[0]));
        }
    }
    
    @Override
    public void markConnectionsInactive(List<String> connectionIds) {
        removeConnections(Set.copyOf(connectionIds));
    }

    @Override
    public long getTotalActiveUsers() {
        Long count = stringRedisTemplate.opsForZSet().zCard(CONNECTIONS_BY_HEARTBEAT_KEY);
        return count != null ? count : 0;
    }

    @Override
    public long getPodActiveUsers(String podId) {
        Long count = stringRedisTemplate.opsForSet().size(POD_CONNECTIONS_KEY_PREFIX + podId);
        return count != null ? count : 0;
    }
}