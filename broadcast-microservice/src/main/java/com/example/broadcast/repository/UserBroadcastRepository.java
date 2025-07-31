package com.example.broadcast.repository;

import com.example.broadcast.model.UserBroadcastMessage;
import org.springframework.jdbc.core.JdbcTemplate;
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
            ps.setObject(5, userBroadcast.getDeliveredAt());
            ps.setObject(6, userBroadcast.getReadAt());
            return ps;
        }, keyHolder);

        if (keyHolder.getKeyList() != null && !keyHolder.getKeyList().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeyList().get(0);
            Number id = (Number) keys.get("ID");
            if (id != null) {
                userBroadcast.setId(id.longValue());
            } else {
                throw new RuntimeException("Generated key 'ID' not found.");
            }
        } else {
            throw new RuntimeException("Failed to retrieve generated key for user broadcast.");
        }
        return userBroadcast;
    }
    
    public Optional<UserBroadcastMessage> findById(Long id) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE id = ?";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, id).stream().findFirst();
    }

    public List<UserBroadcastMessage> findByUserId(String userId) {
        String sql = """
            SELECT ubm.* FROM user_broadcast_messages ubm
            JOIN broadcast_messages bm ON ubm.broadcast_id = bm.id
            WHERE ubm.user_id = ?
            AND bm.status IN ('ACTIVE', 'SCHEDULED')
            AND (bm.expires_at IS NULL OR bm.expires_at > CURRENT_TIMESTAMP)
            ORDER BY ubm.created_at DESC
            """;
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }

    public List<UserBroadcastMessage> findUnreadByUserId(String userId) {
        String sql = """
            SELECT ubm.* FROM user_broadcast_messages ubm
            JOIN broadcast_messages bm ON ubm.broadcast_id = bm.id
            WHERE ubm.user_id = ? 
            AND ubm.read_status = 'UNREAD'
            AND ubm.delivery_status = 'DELIVERED'
            AND bm.status = 'ACTIVE'
            AND (bm.expires_at IS NULL OR bm.expires_at > CURRENT_TIMESTAMP)
            ORDER BY ubm.created_at DESC
            """;
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }

    public List<UserBroadcastMessage> findPendingMessages(String userId) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE user_id = ? AND delivery_status = 'PENDING' ORDER BY created_at ASC";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }

    /**
     * **NEW:** Finds all user broadcast messages for a given broadcast ID.
     * This is used to populate the delivery details in the admin panel.
     * @param broadcastId The ID of the broadcast.
     * @return A list of user broadcast messages.
     */
    public List<UserBroadcastMessage> findByBroadcastId(Long broadcastId) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE broadcast_id = ? ORDER BY user_id";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, broadcastId);
    }
    
    public int updateDeliveryStatus(Long id, String status) {
        String sql = "UPDATE user_broadcast_messages SET delivery_status = ?, delivered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, id);
    }

    public int updateReadStatus(Long id, String status) {
        String sql = "UPDATE user_broadcast_messages SET read_status = ?, read_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, id);
    }

    public int markAsRead(Long id, ZonedDateTime readAt) {
        String sql = "UPDATE user_broadcast_messages SET read_status = 'READ', read_at = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, readAt, id);
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
            ps.setObject(5, ub.getDeliveredAt());
            ps.setObject(6, ub.getReadAt());
        });
    }

    public List<UserBroadcastMessage> findByUserIdAndStatus(String userId, String deliveryStatus, String readStatus) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE user_id = ? AND delivery_status = ? AND read_status = ?";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId, deliveryStatus, readStatus);
    }
}
