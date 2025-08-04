package com.example.broadcast.repository;

import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.model.BroadcastMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.example.broadcast.util.Constants.BroadcastStatus;

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
            .targetIds(parseJsonArray(rs.getString("target_ids")))
            .priority(rs.getString("priority"))
            .category(rs.getString("category"))
            .scheduledAt(rs.getTimestamp("scheduled_at") != null ?
                    rs.getTimestamp("scheduled_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .expiresAt(rs.getTimestamp("expires_at") != null ?
                    rs.getTimestamp("expires_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .createdAt(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC))
            .updatedAt(rs.getTimestamp("updated_at").toInstant().atZone(ZoneOffset.UTC))
            .status(rs.getString("status"))
            .build();
    private final RowMapper<BroadcastResponse> broadcastResponseRowMapper = (rs, rowNum) -> BroadcastResponse.builder()
            .id(rs.getLong("id"))
            .senderId(rs.getString("sender_id"))
            .senderName(rs.getString("sender_name"))
            .content(rs.getString("content"))
            .targetType(rs.getString("target_type"))
            .targetIds(parseJsonArray(rs.getString("target_ids")))
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
            (sender_id, sender_name, content, target_type, target_ids, priority, category, scheduled_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, broadcast.getSenderId());
            ps.setString(2, broadcast.getSenderName());
            ps.setString(3, broadcast.getContent());
            ps.setString(4, broadcast.getTargetType());
            ps.setString(5, toJsonArray(broadcast.getTargetIds()));
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

            ps.setString(10, broadcast.getStatus() != null ? broadcast.getStatus() : BroadcastStatus.ACTIVE.name());
            return ps;
        }, keyHolder);
        
        // START OF FIX: Handle both PostgreSQL (lowercase "id") and H2 (uppercase "ID") generated keys
        if (keyHolder.getKeyList() != null && !keyHolder.getKeyList().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeyList().get(0);
            Number id = (Number) keys.get("id"); // Check for Postgres's lowercase 'id' first
            if (id == null) {
                id = (Number) keys.get("ID"); // Fallback to H2's uppercase 'ID'
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
        // END OF FIX
        
        return broadcast;
    }

    // ... (rest of the file is unchanged)
    public BroadcastMessage update(BroadcastMessage broadcast) {
        String sql = """
            UPDATE broadcast_messages SET
                sender_id = ?,
                sender_name = ?,
                content = ?,
                target_type = ?,
                target_ids = ?,
                priority = ?,
                category = ?,
                scheduled_at = ?,
                expires_at = ?,
                status = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
        """;
        jdbcTemplate.update(sql,
            broadcast.getSenderId(),
            broadcast.getSenderName(),
            broadcast.getContent(),
            broadcast.getTargetType(),
            toJsonArray(broadcast.getTargetIds()),
            broadcast.getPriority(),
            broadcast.getCategory(),
            broadcast.getScheduledAt() != null ? broadcast.getScheduledAt().toOffsetDateTime() : null,
            broadcast.getExpiresAt() != null ? broadcast.getExpiresAt().toOffsetDateTime() : null,
            broadcast.getStatus(),
            broadcast.getId()
        );
        return broadcast;
    }

    public Optional<BroadcastMessage> findById(Long id) {
        String sql = "SELECT * FROM broadcast_messages WHERE id = ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, id).stream().findFirst();
    }

    public Optional<BroadcastResponse> findBroadcastWithStatsById(Long id) {
        String sql = """
            SELECT
                b.*,
                s.total_targeted,
                s.total_delivered,
                s.total_read
            FROM
                broadcast_messages b
            LEFT JOIN
                broadcast_statistics s ON b.id = s.broadcast_id
            WHERE
                b.id = ?
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper, id).stream().findFirst();
    }

    public List<BroadcastResponse> findActiveBroadcastsWithStats() {
        String sql = """
            SELECT
                b.*,
                COALESCE(s.total_targeted, 0) as total_targeted,
                COALESCE(s.total_delivered, 0) as total_delivered,
                COALESCE(s.total_read, 0) as total_read
            FROM
                broadcast_messages b
            LEFT JOIN
                broadcast_statistics s ON b.id = s.broadcast_id
            WHERE
                b.status = 'ACTIVE'
            ORDER BY
                b.created_at DESC
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper);
    }

    public List<BroadcastResponse> findAllBroadcastsWithStats() {
        String sql = """
            SELECT
                b.*,
                COALESCE(s.total_targeted, 0) as total_targeted,
                COALESCE(s.total_delivered, 0) as total_delivered,
                COALESCE(s.total_read, 0) as total_read
            FROM
                broadcast_messages b
            LEFT JOIN
                broadcast_statistics s ON b.id = s.broadcast_id
            ORDER BY
                b.created_at DESC
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper);
    }

    public List<BroadcastResponse> findScheduledBroadcastsWithStats() {
        String sql = """
            SELECT
                b.*,
                COALESCE(s.total_targeted, 0) as total_targeted,
                COALESCE(s.total_delivered, 0) as total_delivered,
                COALESCE(s.total_read, 0) as total_read
            FROM
                broadcast_messages b
            LEFT JOIN
                broadcast_statistics s ON b.id = s.broadcast_id
            WHERE
                b.status = 'SCHEDULED'
            ORDER BY
                b.scheduled_at ASC
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
            FOR UPDATE SKIP LOCKED
            """;
        return jdbcTemplate.query(sql, broadcastRowMapper, now.toOffsetDateTime(), limit);
    }

    public List<BroadcastMessage> findExpiredBroadcasts(ZonedDateTime now) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, now.toOffsetDateTime());
    }

    public int updateStatus(Long broadcastId, String status) {
        String sql = "UPDATE broadcast_messages SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, broadcastId);
    }

    private List<String> parseJsonArray(String json) {
        if (json == null || json.trim().isEmpty()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }
}