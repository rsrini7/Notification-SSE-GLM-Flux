package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.model.UserBroadcastMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class UserBroadcastRepository {

    private final JdbcTemplate jdbcTemplate;
    public UserBroadcastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<UserBroadcastMessage> userBroadcastRowMapper = (rs, rowNum) -> UserBroadcastMessage.builder()
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
            
    public UserBroadcastMessage save(UserBroadcastMessage userBroadcast) {
        String sql = """
            INSERT INTO user_broadcast_messages 
            (broadcast_id, user_id, delivery_status, read_status, delivered_at, read_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, userBroadcast.getBroadcastId());
            ps.setString(2, userBroadcast.getUserId());
            ps.setString(3, userBroadcast.getDeliveryStatus());
            ps.setString(4, userBroadcast.getReadStatus());
            
            if (userBroadcast.getDeliveredAt() != null) {
                ps.setObject(5, userBroadcast.getDeliveredAt().toOffsetDateTime());
            } else {
                ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            if (userBroadcast.getReadAt() != null) {
                ps.setObject(6, userBroadcast.getReadAt().toOffsetDateTime());
            } else {
                ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            return ps;
        }, keyHolder);
        if (keyHolder.getKeyList() != null && !keyHolder.getKeyList().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeyList().get(0);
            Number id = (Number) keys.get("id");
            if (id == null) {
                id = (Number) keys.get("ID");
            }
            if (id != null) {
                userBroadcast.setId(id.longValue());
            } else {
                throw new RuntimeException("Generated key 'id' not found.");
            }
        } else if (keyHolder.getKey() != null) {
             userBroadcast.setId(keyHolder.getKey().longValue());
        } else {
            throw new RuntimeException("Failed to retrieve generated key for user broadcast.");
        }
        
        return userBroadcast;
    }
    
    public Optional<UserBroadcastMessage> findById(Long id) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE id = ?";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, id).stream().findFirst();
    }

    /**
    * This is the updated method using a PreparedStatement callback for consistency and clarity.
    */
    public void update(UserBroadcastMessage message) {
        String sql = "UPDATE user_broadcast_messages SET delivery_status = ?, read_status = ?, delivered_at = ?, read_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, message.getDeliveryStatus());
            ps.setString(2, message.getReadStatus());

            if (message.getDeliveredAt() != null) {
                ps.setObject(3, message.getDeliveredAt().toOffsetDateTime());
            } else {
                ps.setNull(3, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            if (message.getReadAt() != null) {
                ps.setObject(4, message.getReadAt().toOffsetDateTime());
            } else {
                ps.setNull(4, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            ps.setLong(5, message.getId());
            return ps;
        });
    }
    
    public Optional<UserBroadcastMessage> findByUserIdAndBroadcastId(String userId, Long broadcastId) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE user_id = ? AND broadcast_id = ?";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId, broadcastId).stream().findFirst();
    }

    public List<UserBroadcastMessage> findByBroadcastId(Long broadcastId) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE broadcast_id = ? ORDER BY user_id";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, broadcastId);
    }
    
    public int updateDeliveryStatus(Long id, String status) {
        String sql = "UPDATE user_broadcast_messages SET delivery_status = ?, delivered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, id);
    }
    
    public int markAsRead(Long id, ZonedDateTime readAt) {
        String sql = "UPDATE user_broadcast_messages SET read_status = 'READ', read_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND read_status = 'UNREAD'";
        return jdbcTemplate.update(sql, readAt.toOffsetDateTime(), id);
    }

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

            if (ub.getDeliveredAt() != null) {
                ps.setObject(5, ub.getDeliveredAt().toOffsetDateTime());
            } else {
                ps.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
            }
            if (ub.getReadAt() != null) {
                ps.setObject(6, ub.getReadAt().toOffsetDateTime());
            } else {
                ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            }
        });
    }

     /**
     * NEW METHOD: Updates all user messages for a broadcast (both PENDING and DELIVERED) to a new status.
     * This is used during expiration to ensure consistency for all targeted users.
     */
    public int updateNonFinalStatusesByBroadcastId(Long broadcastId, String newStatus) {
        String sql = """
            UPDATE user_broadcast_messages 
            SET delivery_status = ?, updated_at = CURRENT_TIMESTAMP 
            WHERE broadcast_id = ? AND delivery_status IN ('PENDING', 'DELIVERED')
        """;
        return jdbcTemplate.update(sql, newStatus, broadcastId);
    }

    public List<Long> findReadBroadcastIdsByUserId(String userId) {
        String sql = "SELECT broadcast_id FROM user_broadcast_messages WHERE user_id = ? AND read_status = 'READ'";
        return jdbcTemplate.queryForList(sql, Long.class, userId);
    }

    /**
     * Finds all message records for a user that have not been read and delivered.
     * This is used to fetch the primary list of messages for a user's inbox.
     * @param userId The ID of the user.
     * @return A list of unread UserBroadcastMessage objects, sorted by most recent first.
     */
    public List<UserBroadcastMessage> findUnreadPendingDeliveredByUserId(String userId) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE user_id = ? AND read_status = 'UNREAD' AND (delivery_status = 'PENDING' OR delivery_status = 'DELIVERED') ORDER BY created_at DESC";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }

    /**
     * NEW METHOD: Deletes all message records for a specific broadcast
     * that have NOT been marked as 'READ'.
     * This is used by the cleanup service to enforce data retention policies.
     * @param broadcastId The ID of the finalized (EXPIRED or CANCELLED) broadcast.
     * @return The number of rows deleted.
     */
    public int deleteUnreadMessagesByBroadcastId(Long broadcastId) {
        String sql = "DELETE FROM user_broadcast_messages WHERE broadcast_id = ? AND read_status <> 'READ'";
        return jdbcTemplate.update(sql, broadcastId);
    }

}