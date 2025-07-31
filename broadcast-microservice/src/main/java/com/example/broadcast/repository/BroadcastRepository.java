package com.example.broadcast.repository;

import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.model.BroadcastMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Repository for broadcast message operations using Spring JDBC
 * Optimized for high-scale operations with proper indexing and batch operations
 */
@Repository
public class BroadcastRepository {

    private final JdbcTemplate jdbcTemplate;

    public BroadcastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Original RowMapper for the BroadcastMessage model
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

    // New RowMapper to map the JOIN result directly to BroadcastResponse DTO
    private final RowMapper<BroadcastResponse> broadcastResponseRowMapper = (rs, rowNum) -> BroadcastResponse.builder()
            .id(rs.getLong("id"))
            .senderId(rs.getString("sender_id"))
            .senderName(rs.getString("sender_name"))
            .content(rs.getString("content"))
            .targetType(rs.getString("target_type"))
            .targetIds(parseJsonArray(rs.getString("target_ids")))
            .priority(rs.getString("priority"))
            .category(rs.getString("category"))
            .expiresAt(rs.getTimestamp("expires_at") != null ?
                    rs.getTimestamp("expires_at").toInstant().atZone(ZoneOffset.UTC) : null)
            .createdAt(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC))
            .status(rs.getString("status"))
            .totalTargeted(rs.getInt("total_targeted"))
            .totalDelivered(rs.getInt("total_delivered"))
            .totalRead(rs.getInt("total_read"))
            .build();

    /**
     * Create a new broadcast message
     */
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
            ps.setObject(8, broadcast.getScheduledAt());
            ps.setObject(9, broadcast.getExpiresAt());
            ps.setString(10, broadcast.getStatus() != null ? broadcast.getStatus() : "ACTIVE");
            return ps;
        }, keyHolder);

        if (keyHolder.getKeyList() != null && !keyHolder.getKeyList().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeyList().get(0);
            Number id = (Number) keys.get("ID");
            if (id != null) {
                broadcast.setId(id.longValue());
            } else {
                throw new RuntimeException("Generated key 'ID' not found in the returned keys.");
            }
        } else if (keyHolder.getKey() != null) {
            broadcast.setId(keyHolder.getKey().longValue());
        } else {
            throw new RuntimeException("Failed to retrieve generated key for broadcast.");
        }
        
        return broadcast;
    }

    /**
     * Find broadcast by ID (original method)
     */
    public Optional<BroadcastMessage> findById(Long id) {
        String sql = "SELECT * FROM broadcast_messages WHERE id = ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, id).stream().findFirst();
    }

    /**
     * Find a single broadcast with its statistics using a JOIN.
     */
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

    /**
     * Find all active broadcasts with their statistics in a single query to prevent N+1 problem.
     */
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
                AND (b.scheduled_at IS NULL OR b.scheduled_at <= CURRENT_TIMESTAMP)
                AND (b.expires_at IS NULL OR b.expires_at > CURRENT_TIMESTAMP)
            ORDER BY
                b.created_at DESC
            """;
        return jdbcTemplate.query(sql, broadcastResponseRowMapper);
    }

    /**
     * Find scheduled broadcasts that are ready to be processed
     */
    public List<BroadcastMessage> findScheduledBroadcastsToProcess(ZonedDateTime now) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'SCHEDULED' AND scheduled_at <= ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, now);
    }
    
    /**
     * **NEW:** Find active broadcasts that have passed their expiration time.
     */
    public List<BroadcastMessage> findExpiredBroadcasts(ZonedDateTime now) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, now);
    }

    /**
     * Update broadcast status
     */
    public int updateStatus(Long broadcastId, String status) {
        String sql = "UPDATE broadcast_messages SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, broadcastId);
    }

    /**
     * Helper method to parse JSON array from database
     */
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

    /**
     * Helper method to convert list to JSON array string
     */
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
