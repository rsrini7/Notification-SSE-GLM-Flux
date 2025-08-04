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
        // START OF FIX: Replaced H2-specific MERGE with PostgreSQL-compatible INSERT ON CONFLICT
        String sql = """
            INSERT INTO broadcast_statistics (broadcast_id, total_targeted, total_delivered, total_read, total_failed, calculated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (broadcast_id) DO UPDATE SET
                total_targeted = EXCLUDED.total_targeted,
                total_delivered = EXCLUDED.total_delivered,
                total_read = EXCLUDED.total_read,
                total_failed = EXCLUDED.total_failed,
                calculated_at = EXCLUDED.calculated_at
        """;
        // END OF FIX

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setLong(1, stats.getBroadcastId());
            ps.setInt(2, stats.getTotalTargeted());
            ps.setInt(3, stats.getTotalDelivered());
            ps.setInt(4, stats.getTotalRead());
            ps.setInt(5, stats.getTotalFailed());

            if (stats.getCalculatedAt() != null) {
                ps.setObject(6, stats.getCalculatedAt().toOffsetDateTime());
            } else {
                ps.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            return ps;
        });
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