package com.example.broadcast.repository;

import com.example.broadcast.model.UserBroadcastMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Repository for user broadcast message operations using Spring JDBC
 * Optimized for high-scale operations with proper indexing and batch operations
 */
@Repository
public class UserBroadcastRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public UserBroadcastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<UserBroadcastMessage> userBroadcastRowMapper = new RowMapper<>() {
        @Override
        public UserBroadcastMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return UserBroadcastMessage.builder()
                    .id(rs.getLong("id"))
                    .broadcastId(rs.getLong("broadcast_id"))
                    .userId(rs.getString("user_id"))
                    .deliveryStatus(rs.getString("delivery_status"))
                    .readStatus(rs.getString("read_status"))
                    .deliveredAt(rs.getTimestamp("delivered_at") != null ? 
                            rs.getTimestamp("delivered_at").toInstant().atZone(ZoneOffset.UTC) : null)
                    .readAt(rs.getTimestamp("read_at") != null ? 
                            rs.getTimestamp("read_at").toInstant().atZone(ZoneOffset.UTC) : null)
                    .createdAt(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC))
                    .updatedAt(rs.getTimestamp("updated_at").toInstant().atZone(ZoneOffset.UTC))
                    .build();
        }
    };

    /**
     * Create a new user broadcast message
     */
    public UserBroadcastMessage save(UserBroadcastMessage userBroadcast) {
        String sql = """
            INSERT INTO user_broadcast_messages 
            (broadcast_id, user_id, delivery_status, read_status, delivered_at, read_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        
        final Object[] params = new Object[]{
                userBroadcast.getBroadcastId(),
                userBroadcast.getUserId(),
                userBroadcast.getDeliveryStatus(),
                userBroadcast.getReadStatus(),
                userBroadcast.getDeliveredAt(),
                userBroadcast.getReadAt()
        };

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }            return ps;
        }, keyHolder);

        // Get the generated ID from the key map
        if (!keyHolder.getKeyList().isEmpty()) {
            userBroadcast.setId(((Number) keyHolder.getKeyList().get(0).get("ID")).longValue());
        } else {
            throw new RuntimeException("Failed to retrieve generated key for user broadcast.");
        }
        return userBroadcast;
    }

    /**
     * Find user broadcast by ID
     */
    public Optional<UserBroadcastMessage> findById(Long id) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE id = ?";
        List<UserBroadcastMessage> results = jdbcTemplate.query(sql, userBroadcastRowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find user broadcasts by user ID and status
     */
    public List<UserBroadcastMessage> findByUserIdAndStatus(String userId, String deliveryStatus, String readStatus) {
        String sql = """
            SELECT * FROM user_broadcast_messages 
            WHERE user_id = ? 
            AND delivery_status = ? 
            AND read_status = ?
            ORDER BY created_at DESC
            """;
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId, deliveryStatus, readStatus);
    }

    /**
     * Find unread messages for a user
     */
    public List<UserBroadcastMessage> findUnreadMessages(String userId) {
        String sql = """
            SELECT ubm.* FROM user_broadcast_messages ubm
            JOIN broadcast_messages bm ON ubm.broadcast_id = bm.id
            WHERE ubm.user_id = ? 
            AND ubm.read_status = 'UNREAD'
            AND ubm.delivery_status = 'DELIVERED'
            AND bm.status = 'ACTIVE'
            AND (bm.scheduled_at IS NULL OR bm.scheduled_at <= CURRENT_TIMESTAMP)
            AND (bm.expires_at IS NULL OR bm.expires_at > CURRENT_TIMESTAMP)
            ORDER BY ubm.created_at DESC
            """;
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }

    /**
     * Find pending messages for a user (not yet delivered)
     */
    public List<UserBroadcastMessage> findPendingMessages(String userId) {
        String sql = """
            SELECT * FROM user_broadcast_messages 
            WHERE user_id = ? 
            AND delivery_status = 'PENDING'
            ORDER BY created_at ASC
            """;
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }

    /**
     * Update delivery status
     */
    public int updateDeliveryStatus(Long id, String status) {
        String sql = """
            UPDATE user_broadcast_messages 
            SET delivery_status = ?, delivered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP 
            WHERE id = ?
            """;
        return jdbcTemplate.update(sql, status, id);
    }

    /**
     * Update read status
     */
    public int updateReadStatus(Long id, String status) {
        String sql = """
            UPDATE user_broadcast_messages 
            SET read_status = ?, read_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP 
            WHERE id = ?
            """;
        return jdbcTemplate.update(sql, status, id);
    }

    /**
     * Batch insert user broadcast messages for high-performance operations
     */
    public void batchInsert(List<UserBroadcastMessage> userBroadcasts) {
        String sql = """
            INSERT INTO user_broadcast_messages 
            (broadcast_id, user_id, delivery_status, read_status, delivered_at, read_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        
        jdbcTemplate.batchUpdate(sql, userBroadcasts, userBroadcasts.size(), (ps, ub) -> {
            ps.setLong(1, ub.getBroadcastId());
            ps.setString(2, ub.getUserId());
            ps.setString(3, ub.getDeliveryStatus());
            ps.setString(4, ub.getReadStatus());
            ps.setTimestamp(5, ub.getDeliveredAt() != null ? 
                    java.sql.Timestamp.from(ub.getDeliveredAt().toInstant()) : null);
            ps.setTimestamp(6, ub.getReadAt() != null ? 
                    java.sql.Timestamp.from(ub.getReadAt().toInstant()) : null);
        });
    }

    /**
     * Count messages by user and status
     */
    public long countByUserIdAndStatus(String userId, String deliveryStatus, String readStatus) {
        String sql = """
            SELECT COUNT(*) FROM user_broadcast_messages 
            WHERE user_id = ? AND delivery_status = ? AND read_status = ?
            """;
        return jdbcTemplate.queryForObject(sql, Long.class, userId, deliveryStatus, readStatus);
    }

    /**
     * Find messages by broadcast ID
     */
    public List<UserBroadcastMessage> findByBroadcastId(Long broadcastId) {
        String sql = """
            SELECT * FROM user_broadcast_messages 
            WHERE broadcast_id = ? 
            ORDER BY created_at ASC
            """;
        return jdbcTemplate.query(sql, userBroadcastRowMapper, broadcastId);
    }

    /**
     * Get delivery statistics for a broadcast
     */
    public java.util.Map<String, Long> getDeliveryStats(Long broadcastId) {
        String sql = """
            SELECT 
                delivery_status,
                read_status,
                COUNT(*) as count
            FROM user_broadcast_messages 
            WHERE broadcast_id = ? 
            GROUP BY delivery_status, read_status
            """;
        
        List<java.util.Map<String, Object>> results = jdbcTemplate.queryForList(sql, broadcastId);
        java.util.Map<String, Long> stats = new java.util.HashMap<>();
        
        for (java.util.Map<String, Object> row : results) {
            String key = row.get("delivery_status") + "_" + row.get("read_status");
            stats.put(key, (Long) row.get("count"));
        }
        
        return stats;
    }

    /**
     * Mark message as read with specific timestamp
     */
    public int markAsRead(Long id, ZonedDateTime readAt) {
        String sql = """
            UPDATE user_broadcast_messages 
            SET read_status = 'READ', read_at = ?, updated_at = CURRENT_TIMESTAMP 
            WHERE id = ?
            """;
        return jdbcTemplate.update(sql, readAt, id);
    }

     /**
     * Find user broadcasts by user ID
     */
    public List<UserBroadcastMessage> findByUserId(String userId) {
        String sql = """
            SELECT ubm.* FROM user_broadcast_messages ubm
            JOIN broadcast_messages bm ON ubm.broadcast_id = bm.id
            WHERE ubm.user_id = ?
            AND bm.status = 'ACTIVE'
            AND (bm.scheduled_at IS NULL OR bm.scheduled_at <= CURRENT_TIMESTAMP)
            AND (bm.expires_at IS NULL OR bm.expires_at > CURRENT_TIMESTAMP)
            ORDER BY ubm.created_at DESC
            """;
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }

    /**
     * Find unread messages for a user (alias for findUnreadMessages)
     */
    public List<UserBroadcastMessage> findUnreadByUserId(String userId) {
        return findUnreadMessages(userId);
    }
}