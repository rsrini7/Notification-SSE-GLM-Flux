package com.example.broadcast.repository;

import com.example.broadcast.model.BroadcastStatistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
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

    public BroadcastStatistics save(BroadcastStatistics stats) {
        String sql = "INSERT INTO broadcast_statistics (broadcast_id, total_targeted, total_delivered, total_read, total_failed, avg_delivery_time_ms, calculated_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"ID"});
            ps.setLong(1, stats.getBroadcastId());
            ps.setInt(2, stats.getTotalTargeted());
            ps.setInt(3, stats.getTotalDelivered());
            ps.setInt(4, stats.getTotalRead());
            ps.setInt(5, stats.getTotalFailed());
            ps.setObject(6, stats.getAvgDeliveryTimeMs());
            ps.setObject(7, stats.getCalculatedAt());
            return ps;
        }, keyHolder);

        if (keyHolder.getKey() != null) {
            stats.setId(keyHolder.getKey().longValue());
        }
        return stats;
    }

    public Optional<BroadcastStatistics> findByBroadcastId(Long broadcastId) {
        String sql = "SELECT * FROM broadcast_statistics WHERE broadcast_id = ?";
        List<BroadcastStatistics> results = jdbcTemplate.query(sql, rowMapper, broadcastId);
        return results.stream().findFirst();
    }

    public Optional<BroadcastStatistics> findById(Long id) {
        String sql = "SELECT * FROM broadcast_statistics WHERE id = ?";
        List<BroadcastStatistics> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.stream().findFirst();
    }
}