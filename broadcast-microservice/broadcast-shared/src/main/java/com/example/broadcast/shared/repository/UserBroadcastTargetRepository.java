package com.example.broadcast.shared.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class UserBroadcastTargetRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 1000;

    /**
     * Inserts a large list of user IDs for a specific broadcast in batches.
     * @param broadcastId The ID of the broadcast.
     * @param userIds The list of user IDs to insert.
     */
    public void batchInsert(Long broadcastId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO broadcast_user_targets (broadcast_id, user_id) VALUES (?, ?)";

        jdbcTemplate.batchUpdate(sql, userIds, BATCH_SIZE, (PreparedStatement ps, String userId) -> {
            ps.setLong(1, broadcastId);
            ps.setString(2, userId);
        });
    }

    /**
     * Retrieves the list of pre-computed user IDs for a given broadcast.
     * @param broadcastId The ID of the broadcast.
     * @return A list of user ID strings.
     */
    public List<String> findUserIdsByBroadcastId(Long broadcastId) {
        String sql = "SELECT user_id FROM broadcast_user_targets WHERE broadcast_id = ?";
        return jdbcTemplate.queryForList(sql, String.class, broadcastId);
    }

    /**
     * Deletes all target user entries for a given broadcast.
     * @param broadcastId The ID of the broadcast to clean up.
     */
    public int deleteByBroadcastId(Long broadcastId) {
        String sql = "DELETE FROM broadcast_user_targets WHERE broadcast_id = ?";
        return jdbcTemplate.update(sql, broadcastId);
    }
    
}