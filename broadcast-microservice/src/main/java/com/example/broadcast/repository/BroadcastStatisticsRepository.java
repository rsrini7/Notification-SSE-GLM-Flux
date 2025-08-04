package com.example.broadcast.repository;

import com.example.broadcast.model.BroadcastStatistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.time.ZoneOffset;

@Repository
public class BroadcastStatisticsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<BroadcastStatistics> rowMapper;
    public BroadcastStatisticsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = (rs, rowNum) -> BroadcastStatistics.builder()
                .id(rs.getLong("id"))
                .broadcastId(rs.getLong("broadcast_id"))
                .totalTargeted(rs.getInt("total_targeted"))
                .totalDelivered(rs.getInt("total_delivered"))
                .totalRead(rs.getInt("total_read"))
                .totalFailed(rs.getInt("total_failed"))
                .avgDeliveryTimeMs(rs.getObject("avg_delivery_time_ms", Long.class))
                .calculatedAt(rs.getTimestamp("calculated_at").toInstant().atZone(ZoneOffset.UTC))
                .build();
    }

    public void save(BroadcastStatistics stats) {
        // START OF FIX: Replaced ON CONFLICT with a standard MERGE statement compatible with both H2 and PostgreSQL 15+
        String sql = """
            MERGE INTO broadcast_statistics t
            USING (VALUES (?, ?, ?, ?, ?, ?))
                AS s(broadcast_id, total_targeted, total_delivered, total_read, total_failed, calculated_at)
            ON t.broadcast_id = s.broadcast_id
            WHEN MATCHED THEN
                UPDATE SET total_targeted = s.total_targeted,
                           total_delivered = s.total_delivered,
                           total_read = s.total_read,
                           total_failed = s.total_failed,
                           calculated_at = s.calculated_at
            WHEN NOT MATCHED THEN
                INSERT (broadcast_id, total_targeted, total_delivered, total_read, total_failed, calculated_at)
                VALUES (s.broadcast_id, s.total_targeted, s.total_delivered, s.total_read, s.total_failed, s.calculated_at)
            """;
        
        jdbcTemplate.update(sql,
                stats.getBroadcastId(),
                stats.getTotalTargeted(),
                stats.getTotalDelivered(),
                stats.getTotalRead(),
                stats.getTotalFailed(),
                stats.getCalculatedAt() != null ? stats.getCalculatedAt().toOffsetDateTime() : null
        );
        // END OF FIX
    }

    public Optional<BroadcastStatistics> findByBroadcastId(Long broadcastId) {
        String sql = "SELECT * FROM broadcast_statistics WHERE broadcast_id = ?";
        List<BroadcastStatistics> results = jdbcTemplate.query(sql, rowMapper, broadcastId);
        return results.stream().findFirst();
    }

    public int incrementDeliveredCount(Long broadcastId) {
        String sql = "UPDATE broadcast_statistics SET total_delivered = total_delivered + 1, calculated_at = CURRENT_TIMESTAMP WHERE broadcast_id = ?";
        return jdbcTemplate.update(sql, broadcastId);
    }

    public int incrementReadCount(Long broadcastId) {
        String sql = "UPDATE broadcast_statistics SET total_read = total_read + 1, calculated_at = CURRENT_TIMESTAMP WHERE broadcast_id = ?";
        return jdbcTemplate.update(sql, broadcastId);
    }
}