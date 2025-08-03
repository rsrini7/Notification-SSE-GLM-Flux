package com.example.broadcast.repository;

import com.example.broadcast.model.UserSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.time.ZoneOffset;

/**
 * Repository for user session operations using Spring JDBC
 * Used for tracking user connections and pod assignments
 */
@Repository
public class UserSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<UserSession> sessionRowMapper = new RowMapper<>() {
        @Override
        public UserSession mapRow(ResultSet rs, int rowNum) throws SQLException {
            return UserSession.builder()
                    .id(rs.getLong("id"))
                    .userId(rs.getString("user_id"))
                    .sessionId(rs.getString("session_id"))
                    .podId(rs.getString("pod_id"))
                    .connectionStatus(rs.getString("connection_status"))
                    .connectedAt(rs.getTimestamp("connected_at").toInstant().atZone(ZoneOffset.UTC))
                    .disconnectedAt(rs.getTimestamp("disconnected_at") != null ? 
                            rs.getTimestamp("disconnected_at").toInstant().atZone(ZoneOffset.UTC) : null)
                    .lastHeartbeat(rs.getTimestamp("last_heartbeat").toInstant().atZone(ZoneOffset.UTC))
                    .build();
        }
    };

    /**
     * Create or update user session
     */
    public UserSession save(UserSession session) {
        String sql = """
            MERGE INTO user_sessions AS t
            USING (
                SELECT
                    CAST(? AS VARCHAR(255)) AS user_id,
                    CAST(? AS VARCHAR(255)) AS session_id,
                    CAST(? AS VARCHAR(255)) AS pod_id,
                    CAST(? AS VARCHAR(20)) AS connection_status,
                    CAST(? AS TIMESTAMP WITH TIME ZONE) AS connected_at,
                    CAST(? AS TIMESTAMP WITH TIME ZONE) AS last_heartbeat
            ) AS s ON t.user_id = s.user_id AND t.session_id = s.session_id
            WHEN MATCHED THEN
                UPDATE SET
                    connection_status = s.connection_status,
                    pod_id = s.pod_id,
                    last_heartbeat = s.last_heartbeat,
                    disconnected_at = CASE WHEN s.connection_status = 'INACTIVE' THEN CURRENT_TIMESTAMP ELSE NULL END
            WHEN NOT MATCHED THEN
                INSERT (user_id, session_id, pod_id, connection_status, connected_at, last_heartbeat)
                VALUES (s.user_id, s.session_id, s.pod_id, s.connection_status, s.connected_at, s.last_heartbeat)
            """;
        jdbcTemplate.update(sql,
                session.getUserId(),
                session.getSessionId(),
                session.getPodId(),
                session.getConnectionStatus(),
                session.getConnectedAt(),
                session.getLastHeartbeat());
        return session;
    }

    /**
     * Find session by user ID
     */
    public Optional<UserSession> findByUserId(String userId) {
        String sql = """
            SELECT * FROM user_sessions 
            WHERE user_id = ? AND connection_status = 'ACTIVE'
            ORDER BY last_heartbeat DESC
            LIMIT 1
            """;
        List<UserSession> results = jdbcTemplate.query(sql, sessionRowMapper, userId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find sessions by pod ID
     */
    public List<UserSession> findByPodId(String podId) {
        String sql = """
            SELECT * FROM user_sessions 
            WHERE pod_id = ? AND connection_status = 'ACTIVE'
            ORDER BY connected_at DESC
            """;
        return jdbcTemplate.query(sql, sessionRowMapper, podId);
    }

    // REMOVED: updateHeartbeat is no longer needed as the client will not be polling.

    /**
     * Mark session as inactive
     */
    public int markSessionInactive(String sessionId, String podId) {
        String sql = """
            UPDATE user_sessions 
            SET connection_status = 'INACTIVE', 
                disconnected_at = CURRENT_TIMESTAMP 
            WHERE session_id = ? AND pod_id = ?
            """;
        return jdbcTemplate.update(sql, sessionId, podId);
    }
    
    /**
     * NEW: Marks all active sessions for a given list of user IDs as INACTIVE.
     * This is used by the cleanup service to handle dropped connections.
     * @param userIds The list of user IDs whose sessions should be marked inactive.
     * @return The number of rows affected.
     */
    public int markSessionsInactiveForUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        String sql = String.format(
            "UPDATE user_sessions SET connection_status = 'INACTIVE', disconnected_at = CURRENT_TIMESTAMP WHERE connection_status = 'ACTIVE' AND user_id IN (%s)",
            String.join(",", java.util.Collections.nCopies(userIds.size(), "?"))
        );
        return jdbcTemplate.update(sql, userIds.toArray());
    }


    /**
     * Get active user count by pod
     */
    public long getActiveUserCountByPod(String podId) {
        String sql = """
            SELECT COUNT(*) FROM user_sessions 
            WHERE pod_id = ? AND connection_status = 'ACTIVE'
            """;
        return jdbcTemplate.queryForObject(sql, Long.class, podId);
    }

    /**
     * Get total active user count
     */
    public long getTotalActiveUserCount() {
        String sql = """
            SELECT COUNT(*) FROM user_sessions 
            WHERE connection_status = 'ACTIVE'
            """;
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    // REMOVED: cleanupExpiredSessions is no longer needed. The new cleanup logic in SseService is more reliable.
    
    /**
     * Get all user IDs that are currently marked as active in the database.
     */
    public List<String> findAllActiveUserIds() {
        String sql = "SELECT DISTINCT user_id FROM user_sessions WHERE connection_status = 'ACTIVE'";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * Batch insert sessions for high-performance operations
     */
    public void batchInsert(List<UserSession> sessions) {
        String sql = """
            INSERT INTO user_sessions 
            (user_id, session_id, pod_id, connection_status, connected_at, last_heartbeat)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        
        jdbcTemplate.batchUpdate(sql, sessions, sessions.size(), (ps, session) -> {
            ps.setString(1, session.getUserId());
            ps.setString(2, session.getSessionId());
            ps.setString(3, session.getPodId());
            ps.setString(4, session.getConnectionStatus());
            ps.setTimestamp(5, java.sql.Timestamp.from(session.getConnectedAt().toInstant()));
            ps.setTimestamp(6, java.sql.Timestamp.from(session.getLastHeartbeat().toInstant()));
        });
    }
}