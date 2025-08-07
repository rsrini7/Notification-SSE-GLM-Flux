package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.cache.UserSessionInfo;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An abstraction for distributed session management.
 * Implementations of this interface will provide the logic for either a Redis-based
 * or database-backed session store.
 */
public interface DistributedSessionManager {

    void registerSession(String userId, String sessionId, String podId);

    void removeSession(String userId, String sessionId, String podId);

    void updateHeartbeats(String podId, Set<String> sessionIds);

    Set<String> getStaleSessionIds(long thresholdTimestamp);

    Optional<UserSessionInfo> getSessionDetails(String sessionId);

    void removeSessions(Set<String> sessionIds);

    void markSessionsInactive(List<String> sessionIds);

    long getTotalActiveUsers();

    long getPodActiveUsers(String podId);
}