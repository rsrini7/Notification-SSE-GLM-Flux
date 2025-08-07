package com.example.broadcast.shared.repository;

import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.util.Constants.DeliveryStatus;
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
            
    private final RowMapper<UserBroadcastResponse> userBroadcastResponseRowMapper = (rs, rowNum) -> UserBroadcastResponse.builder()
            .id(rs.getLong("id"))
            .broadcastId(rs.getLong("broadcast_id"))
            .userId(rs.getString("user_id"))
            .deliveryStatus(rs.getString("delivery_status"))
            .readStatus(rs.getString("read_status"))
            .deliveredAt(rs.getTimestamp("delivered_at") != null ? rs.getTimestamp("delivered_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .readAt(rs.getTimestamp("read_at") != null ? rs.getTimestamp("read_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .createdAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .senderName(rs.getString("sender_name"))
            .content(rs.getString("content"))
            .priority(rs.getString("priority"))
            .category(rs.getString("category"))
            .broadcastCreatedAt(rs.getTimestamp("broadcast_created_at").toInstant().atZone(ZoneOffset.UTC))
            .scheduledAt(rs.getTimestamp("scheduled_at") != null ?
                rs.getTimestamp("scheduled_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .expiresAt(rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant().atZone(ZoneOffset.UTC) : null)
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

    public List<UserBroadcastResponse> findUserMessagesByUserId(String userId) {
        String sql = """
            SELECT
                ubm.id, ubm.broadcast_id, ubm.user_id, ubm.delivery_status, ubm.read_status,
                ubm.delivered_at, ubm.read_at, ubm.created_at,
                bm.sender_name, bm.content, bm.priority, bm.category,
                bm.created_at as broadcast_created_at, bm.scheduled_at, bm.expires_at
            FROM
                user_broadcast_messages ubm
            JOIN
                broadcast_messages bm ON ubm.broadcast_id = bm.id
            WHERE
                ubm.user_id = ?
                AND ubm.delivery_status != 'FAILED'
                AND ubm.read_status = 'UNREAD'
                AND bm.status IN ('ACTIVE', 'SCHEDULED')
                AND (bm.expires_at IS NULL OR bm.expires_at > CURRENT_TIMESTAMP)
            ORDER BY
                ubm.created_at DESC
            """;
        return jdbcTemplate.query(sql, userBroadcastResponseRowMapper, userId);
    }
    
    public List<UserBroadcastResponse> findUnreadMessagesByUserId(String userId) {
        String sql = """
            SELECT
                ubm.id, ubm.broadcast_id, ubm.user_id, ubm.delivery_status, ubm.read_status,
                ubm.delivered_at, ubm.read_at, ubm.created_at,
                bm.sender_name, bm.content, bm.priority, bm.category,
                bm.created_at as broadcast_created_at, bm.scheduled_at, bm.expires_at
            FROM
                user_broadcast_messages ubm
            JOIN
                broadcast_messages bm ON ubm.broadcast_id = bm.id
            WHERE
                ubm.user_id = ?
                AND ubm.read_status = 'UNREAD'
                AND ubm.delivery_status = 'DELIVERED'
                AND bm.status = 'ACTIVE'
                AND (bm.expires_at IS NULL OR bm.expires_at > CURRENT_TIMESTAMP)
            ORDER BY
                ubm.created_at DESC
            """;
        return jdbcTemplate.query(sql, userBroadcastResponseRowMapper, userId);
    }

    public List<UserBroadcastMessage> findPendingMessages(String userId) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE user_id = ? AND delivery_status = 'PENDING' ORDER BY created_at ASC";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId);
    }
    
    public Optional<UserBroadcastMessage> findByUserIdAndBroadcastId(String userId, Long broadcastId) {
        String sql = "SELECT * FROM user_broadcast_messages WHERE user_id = ? AND broadcast_id = ?";
        return jdbcTemplate.query(sql, userBroadcastRowMapper, userId, broadcastId).stream().findFirst();
    }
    
    public int updateStatusToPending(Long id) {
        String sql = "UPDATE user_broadcast_messages SET delivery_status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, DeliveryStatus.PENDING.name(), id);
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

    public int updatePendingStatusesByBroadcastId(Long broadcastId, String newStatus) {
        String sql = """
            UPDATE user_broadcast_messages 
            SET delivery_status = ?, updated_at = CURRENT_TIMESTAMP 
            WHERE broadcast_id = ? AND delivery_status = 'PENDING'
        """;
        return jdbcTemplate.update(sql, newStatus, broadcastId);
    }
}