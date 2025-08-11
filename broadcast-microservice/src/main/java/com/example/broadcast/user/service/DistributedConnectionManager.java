package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An abstraction for distributed connection management.
 * Implementations of this interface will provide the logic for either a Redis-based
 * or database-backed store.
 */
// CHANGED: Renamed from "Session" to "Connection" throughout
public interface DistributedConnectionManager {

    void registerConnection(String userId, String connectionId, String podId);
    void removeConnection(String userId, String connectionId, String podId);

    void updateHeartbeats(String podId, Set<String> connectionIds);

    Set<String> getStaleConnectionIds(long thresholdTimestamp);

    Optional<UserConnectionInfo> getConnectionDetails(String connectionId);
    void removeConnections(Set<String> connectionIds);

    void markConnectionsInactive(List<String> connectionIds);

    long getTotalActiveUsers();

    long getPodActiveUsers(String podId);
}