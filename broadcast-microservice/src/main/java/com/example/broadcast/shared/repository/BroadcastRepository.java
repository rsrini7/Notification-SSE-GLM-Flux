package com.example.broadcast.shared.repository;

import com.example.broadcast.admin.dto.BroadcastResponse;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.util.Constants.BroadcastStatus;
import com.example.broadcast.shared.util.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class BroadcastRepository {

    private final JdbcTemplate jdbcTemplate;

    public BroadcastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<BroadcastMessage> broadcastRowMapper = (rs, rowNum) -> BroadcastMessage.builder()
            .id(rs.getLong("id"))
            .senderId(rs.getString("sender_id"))
            .senderName(rs.getString("sender_name"))
            .content(rs.getString("content"))
            .targetType(rs.getString("target_type"))
            .targetIds(JsonUtils.parseJsonArray(rs.getString("target_ids")))
            .priority(rs.getString("priority"))
            .category(rs.getString("category"))
            .scheduledAt(rs.getTimestamp("scheduled_at") != null ?
                    rs.getTimestamp("scheduled_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .expiresAt(rs.getTimestamp("expires_at") != null ?
                    rs.getTimestamp("expires_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .createdAt(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC))
            .updatedAt(rs.getTimestamp("updated_at").toInstant().atZone(ZoneOffset.UTC))
            .status(rs.getString("status"))
            .isFireAndForget(rs.getBoolean("is_fire_and_forget"))
            .build();

    private final RowMapper<BroadcastResponse> broadcastResponseRowMapper = (rs, rowNum) -> BroadcastResponse.builder()
            .id(rs.getLong("id"))
            .senderId(rs.getString("sender_id"))
            .senderName(rs.getString("sender_name"))
            .content(rs.getString("content"))
            .targetType(rs.getString("target_type"))
            .targetIds(JsonUtils.parseJsonArray(rs.getString("target_ids")))
            .priority(rs.getString("priority"))
            .category(rs.getString("category"))
            .scheduledAt(rs.getTimestamp("scheduled_at") != null ?
                    rs.getTimestamp("scheduled_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .expiresAt(rs.getTimestamp("expires_at") != null ?
                    rs.getTimestamp("expires_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .createdAt(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC))
            .status(rs.getString("status"))
            .totalTargeted(rs.getInt("total_targeted"))
            .totalDelivered(rs.getInt("total_delivered"))
            .totalRead(rs.getInt("total_read"))
            .build();

    public BroadcastMessage save(BroadcastMessage broadcast) {
        String sql = """
            INSERT INTO broadcast_messages
            (sender_id, sender_name, content, target_type, target_ids, priority, category, scheduled_at, expires_at, status, is_fire_and_forget)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, broadcast.getSenderId());
            ps.setString(2, broadcast.getSenderName());
            ps.setString(3, broadcast.getContent());
            ps.setString(4, broadcast.getTargetType());
            ps.setString(5, JsonUtils.toJsonArray(broadcast.getTargetIds()));
            ps.setString(6, broadcast.getPriority());
            ps.setString(7, broadcast.getCategory());

            if (broadcast.getScheduledAt() != null) {
                ps.setObject(8, broadcast.getScheduledAt().toOffsetDateTime());
            } else {
                ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            }
        
            if (broadcast.getExpiresAt() != null) {
                ps.setObject(9, broadcast.getExpiresAt().toOffsetDateTime());
            } else {
                ps.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            ps.setString(10, broadcast.getStatus() != null ?
                    broadcast.getStatus() : BroadcastStatus.ACTIVE.name());
            
            ps.setBoolean(11, broadcast.isFireAndForget());

            return ps;
        }, keyHolder);
        
        if (keyHolder.getKeyList() != null && !keyHolder.getKeyList().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeyList().get(0);
            Number id = (Number) keys.get("id");
            if (id == null) {
                id = (Number) keys.get("ID");
            }
            if (id != null) {
                broadcast.setId(id.longValue());
            } else {
                throw new RuntimeException("Generated key 'id' not found in the returned keys.");
            }
        } else if (keyHolder.getKey() != null) {
            broadcast.setId(keyHolder.getKey().longValue());
        } else {
            throw new RuntimeException("Failed to retrieve generated key for broadcast.");
        }
        
        return broadcast;
    }

    public BroadcastMessage update(BroadcastMessage broadcast) {
        String sql = """
            UPDATE broadcast_messages SET
                sender_id = ?, sender_name = ?, content = ?, target_type = ?, target_ids = ?,
                priority = ?, category = ?, scheduled_at = ?, expires_at = ?, status = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        jdbcTemplate.update(sql,
            broadcast.getSenderId(), broadcast.getSenderName(), broadcast.getContent(),
            broadcast.getTargetType(), JsonUtils.toJsonArray(broadcast.getTargetIds()),
            broadcast.getPriority(), broadcast.getCategory(),
            broadcast.getScheduledAt() != null ? broadcast.getScheduledAt().toOffsetDateTime() : null,
            broadcast.getExpiresAt() != null ? broadcast.getExpiresAt().toOffsetDateTime() : null,
            broadcast.getStatus(), broadcast.getId()
        );
        return broadcast;
    }

    public Optional<BroadcastMessage> findById(Long id) {
        String sql = "SELECT * FROM broadcast_messages WHERE id = ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, id).stream().findFirst();
    }

    public Optional<BroadcastResponse> findBroadcastWithStatsById(Long id) {
        String sql = """
            SELECT b.*, s.total_targeted, s.total_delivered, s.total_read
            FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
            WHERE b.id = ?
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper, id).stream().findFirst();
    }

    public List<BroadcastResponse> findActiveBroadcastsWithStats() {
        String sql = """
            SELECT b.*, COALESCE(s.total_targeted, 0) as total_targeted,
                         COALESCE(s.total_delivered, 0) as total_delivered,
                         COALESCE(s.total_read, 0) as total_read
            FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
            WHERE b.status = 'ACTIVE' ORDER BY b.created_at DESC
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper);
    }

    public List<BroadcastResponse> findAllBroadcastsWithStats() {
        String sql = """
            SELECT b.*, COALESCE(s.total_targeted, 0) as total_targeted,
                         COALESCE(s.total_delivered, 0) as total_delivered,
                         COALESCE(s.total_read, 0) as total_read
            FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
            ORDER BY b.created_at DESC
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper);
    }

    public List<BroadcastResponse> findScheduledBroadcastsWithStats() {
        String sql = """
            SELECT b.*, COALESCE(s.total_targeted, 0) as total_targeted,
                         COALESCE(s.total_delivered, 0) as total_delivered,
                         COALESCE(s.total_read, 0) as total_read
            FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
            WHERE b.status = 'SCHEDULED' ORDER BY b.scheduled_at ASC
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper);
    }

    @Transactional
    public List<BroadcastMessage> findAndLockScheduledBroadcastsToProcess(ZonedDateTime now, int limit) {
        String sql = """
            SELECT * FROM broadcast_messages
            WHERE status = 'SCHEDULED' AND scheduled_at <= ?
            ORDER BY scheduled_at
            LIMIT ?
            """;
        return jdbcTemplate.query(sql, broadcastRowMapper, now.toOffsetDateTime(), limit);
    }

    public List<BroadcastMessage> findExpiredBroadcasts(ZonedDateTime now) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, now.toOffsetDateTime());
    }
    
    // **NEW METHOD**
    public List<BroadcastMessage> findActiveBroadcastsByTargetType(String targetType) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND target_type = ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, targetType);
    }

    // **NEW METHOD**
    public List<BroadcastMessage> findActiveBroadcastsByTargetTypeAndIds(String targetType, List<String> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return List.of();
        }
        // This query checks if any of the provided targetIds exist in the JSON array stored in the database.
        // This is a simplified approach; for very large scale, a normalized table would be better.
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND target_type = ? AND " +
                     "EXISTS (SELECT 1 FROM json_array_elements_text(target_ids) as elem WHERE elem.value IN (" +
                     String.join(",", java.util.Collections.nCopies(targetIds.size(), "?")) + "))";
        return jdbcTemplate.query(sql, broadcastRowMapper, targetIds.toArray());
    }


    public int updateStatus(Long broadcastId, String status) {
        String sql = "UPDATE broadcast_messages SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, broadcastId);
    }
}