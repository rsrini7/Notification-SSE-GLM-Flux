package com.example.broadcast.repository;

import com.example.broadcast.model.BroadcastMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for broadcast message operations using Spring JDBC
 * Optimized for high-scale operations with proper indexing and batch operations
 */
@Repository
public class BroadcastRepository {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public BroadcastRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<BroadcastMessage> broadcastRowMapper = new RowMapper<>() {
        @Override
        public BroadcastMessage mapRow(ResultSet rs, int rowNum) throws SQLException {
            return BroadcastMessage.builder()
                    .id(rs.getLong("id"))
                    .senderId(rs.getString("sender_id"))
                    .senderName(rs.getString("sender_name"))
                    .content(rs.getString("content"))
                    .targetType(rs.getString("target_type"))
                    .targetIds(parseJsonArray(rs.getString("target_ids")))
                    .priority(rs.getString("priority"))
                    .category(rs.getString("category"))
                    .expiresAt(rs.getTimestamp("expires_at") != null ? 
                            rs.getTimestamp("expires_at").toLocalDateTime() : null)
                    .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                    .updatedAt(rs.getTimestamp("updated_at").toLocalDateTime())
                    .status(rs.getString("status"))
                    .build();
        }
    };

    /**
     * Create a new broadcast message
     */
    public BroadcastMessage save(BroadcastMessage broadcast) {
        String sql = """
            INSERT INTO broadcast_messages 
            (sender_id, sender_name, content, target_type, target_ids, priority, category, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        jdbcTemplate.update(sql,
                broadcast.getSenderId(),
                broadcast.getSenderName(),
                broadcast.getContent(),
                broadcast.getTargetType(),
                toJsonArray(broadcast.getTargetIds()),
                broadcast.getPriority(),
                broadcast.getCategory(),
                broadcast.getExpiresAt(),
                broadcast.getStatus() != null ? broadcast.getStatus() : "ACTIVE");

        // Get the generated ID
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        broadcast.setId(id);
        return broadcast;
    }

    /**
     * Find broadcast by ID
     */
    public Optional<BroadcastMessage> findById(Long id) {
        String sql = "SELECT * FROM broadcast_messages WHERE id = ?";
        List<BroadcastMessage> results = jdbcTemplate.query(sql, broadcastRowMapper, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Find all active broadcasts
     */
    public List<BroadcastMessage> findActiveBroadcasts() {
        String sql = """
            SELECT * FROM broadcast_messages 
            WHERE status = 'ACTIVE' 
            AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
            ORDER BY created_at DESC
            """;
        return jdbcTemplate.query(sql, broadcastRowMapper);
    }

    /**
     * Find broadcasts by sender
     */
    public List<BroadcastMessage> findBySender(String senderId) {
        String sql = """
            SELECT * FROM broadcast_messages 
            WHERE sender_id = ? 
            ORDER BY created_at DESC
            """;
        return jdbcTemplate.query(sql, broadcastRowMapper, senderId);
    }

    /**
     * Update broadcast status
     */
    public int updateStatus(Long broadcastId, String status) {
        String sql = "UPDATE broadcast_messages SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, broadcastId);
    }

    /**
     * Find broadcasts that need to be expired
     */
    public List<BroadcastMessage> findExpiredBroadcasts() {
        String sql = """
            SELECT * FROM broadcast_messages 
            WHERE status = 'ACTIVE' 
            AND expires_at IS NOT NULL 
            AND expires_at <= CURRENT_TIMESTAMP
            """;
        return jdbcTemplate.query(sql, broadcastRowMapper);
    }

    /**
     * Batch insert broadcasts for high-performance operations
     */
    public void batchInsert(List<BroadcastMessage> broadcasts) {
        String sql = """
            INSERT INTO broadcast_messages 
            (sender_id, sender_name, content, target_type, target_ids, priority, category, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        
        jdbcTemplate.batchUpdate(sql, broadcasts, broadcasts.size(), (ps, broadcast) -> {
            ps.setString(1, broadcast.getSenderId());
            ps.setString(2, broadcast.getSenderName());
            ps.setString(3, broadcast.getContent());
            ps.setString(4, broadcast.getTargetType());
            ps.setString(5, toJsonArray(broadcast.getTargetIds()));
            ps.setString(6, broadcast.getPriority());
            ps.setString(7, broadcast.getCategory());
            ps.setTimestamp(8, broadcast.getExpiresAt() != null ? 
                    java.sql.Timestamp.valueOf(broadcast.getExpiresAt()) : null);
            ps.setString(9, broadcast.getStatus() != null ? broadcast.getStatus() : "ACTIVE");
        });
    }

    /**
     * Count broadcasts by status
     */
    public long countByStatus(String status) {
        String sql = "SELECT COUNT(*) FROM broadcast_messages WHERE status = ?";
        return jdbcTemplate.queryForObject(sql, Long.class, status);
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