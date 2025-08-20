package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.dto.admin.BroadcastResponse;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
    
    public List<BroadcastMessage> findActiveBroadcastsByTargetType(String targetType) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND target_type = ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, targetType);
    }

    public List<BroadcastMessage> findActiveBroadcastsByTargetTypeAndIds(String targetType, List<String> targetIds) {
        if (targetIds == null || targetIds.isEmpty()) {
            return List.of();
        }

        // **DATABASE-AGNOSTIC STRATEGY**
        // 1. Fetch all active broadcasts for the given target type. This is a simple, compatible query.
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND target_type = ?";
        List<BroadcastMessage> allActiveBroadcasts = jdbcTemplate.query(sql, broadcastRowMapper, targetType);

        // 2. Filter the results in the Java application code. This is guaranteed to work across any database.
        return allActiveBroadcasts.stream()
                .filter(broadcast -> broadcast.getTargetIds() != null && 
                                     !Collections.disjoint(broadcast.getTargetIds(), targetIds))
                .collect(Collectors.toList());
    }


    public int updateStatus(Long broadcastId, String status) {
        String sql = "UPDATE broadcast_messages SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?";
        return jdbcTemplate.update(sql, status, broadcastId);
    }

    public List<BroadcastMessage> findScheduledBroadcastsWithinWindow(ZonedDateTime cutoffTime) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'SCHEDULED' AND scheduled_at <= ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, cutoffTime.toOffsetDateTime());
    }

    // NEW METHOD for the Scheduling Service
    @Transactional
    public List<BroadcastMessage> findAndLockReadyBroadcastsToProcess(ZonedDateTime now, int limit) {
        String sql = """
            SELECT * FROM broadcast_messages
            WHERE status = 'READY' AND (scheduled_at IS NULL OR scheduled_at <= ?)
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
        return jdbcTemplate.query(sql, broadcastRowMapper, now.toOffsetDateTime(), limit);
    }

    public List<BroadcastMessage> findFinalizedBroadcastsForCleanup(ZonedDateTime cutoff) {
        String sql = "SELECT * FROM broadcast_messages WHERE status IN ('CANCELLED', 'EXPIRED') AND updated_at < ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, cutoff.toOffsetDateTime());
    }

    /**
     * Finds active broadcasts of type 'SELECTED' where the target_ids list contains the given userId.
     * WARNING: This query uses a LIKE pattern which is not performant and can cause full table scans.
     * For a production system, the data model should be normalized with a dedicated join table
     * and an index on the user_id column to make this lookup efficient.
     * @param userId The ID of the user to check for.
     * @return A list of matching broadcast messages.
     */
    public List<BroadcastMessage> findActiveSelectedBroadcastsForUser(String userId) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND target_type = 'SELECTED' AND target_ids LIKE ?";
        // The '%' wildcards are necessary to find the userId within the JSON array string `["user-a", "user-b"]`
        String searchTerm = "%\"" + userId + "\"%"; 
        return jdbcTemplate.query(sql, broadcastRowMapper, searchTerm);
    }

    // For BroadcastPrecomputationService
    public List<BroadcastMessage> findScheduledProductBroadcastsWithinWindow(ZonedDateTime cutoffTime) {
        String sql = "SELECT * FROM broadcast_messages WHERE status = 'SCHEDULED' AND target_type = 'PRODUCT' AND scheduled_at <= ?";
        return jdbcTemplate.query(sql, broadcastRowMapper, cutoffTime.toOffsetDateTime());
    }

    // For BroadcastSchedulingService
    public List<BroadcastMessage> findAndLockScheduledFanOutOnReadBroadcasts(ZonedDateTime now, int limit) {
        String sql = """
            SELECT * FROM broadcast_messages
            WHERE status = 'SCHEDULED' AND target_type IN ('ALL', 'ROLE', 'SELECTED') AND scheduled_at <= ?
            ORDER BY scheduled_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
        return jdbcTemplate.query(sql, broadcastRowMapper, now.toOffsetDateTime(), limit);
    }
    
}