package com.example.broadcast.repository;

import com.example.broadcast.dto.DltMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class DltRepository {

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<DltMessage> rowMapper = (rs, rowNum) -> DltMessage.builder()
            .id(rs.getString("id"))
            .originalTopic(rs.getString("original_topic"))
            .originalPartition(rs.getInt("original_partition"))
            .originalOffset(rs.getLong("original_offset"))
            .exceptionMessage(rs.getString("exception_message"))
            .failedAt(rs.getTimestamp("failed_at").toInstant().atZone(ZoneOffset.UTC))
            .originalMessagePayload(rs.getString("original_message_payload"))
            .build();

    public void save(DltMessage dltMessage) {
        String sql = "INSERT INTO dlt_messages (id, original_topic, original_partition, original_offset, exception_message, original_message_payload, failed_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql,
                dltMessage.getId(),
                dltMessage.getOriginalTopic(),
                dltMessage.getOriginalPartition(),
                dltMessage.getOriginalOffset(),
                dltMessage.getExceptionMessage(),
                dltMessage.getOriginalMessagePayload(),
                dltMessage.getFailedAt());
    }

    public List<DltMessage> findAll() {
        String sql = "SELECT * FROM dlt_messages ORDER BY failed_at DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    // FIX: Changed the return type to Optional<DltMessage> to avoid exceptions on not found.
    // This provides a safer way for the service layer to handle cases where the message might have been processed by another instance.
    public Optional<DltMessage> findById(String id) {
        String sql = "SELECT * FROM dlt_messages WHERE id = ?";
        return jdbcTemplate.query(sql, rowMapper, id).stream().findFirst();
    }

    public void deleteById(String id) {
        String sql = "DELETE FROM dlt_messages WHERE id = ?";
        jdbcTemplate.update(sql, id);
    }
}