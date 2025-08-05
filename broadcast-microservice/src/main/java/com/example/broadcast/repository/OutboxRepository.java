package com.example.broadcast.repository;

import com.example.broadcast.model.OutboxEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public OutboxRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<OutboxEvent> rowMapper = (rs, rowNum) -> OutboxEvent.builder()
            .id(rs.getObject("id", UUID.class))
            .aggregateType(rs.getString("aggregate_type"))
            .aggregateId(rs.getString("aggregate_id"))
            .eventType(rs.getString("event_type"))
            .topic(rs.getString("topic")) // Add this line
            .payload(rs.getString("payload"))
            .createdAt(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC))
            .build();

    public void save(OutboxEvent event) {
        String sql = "INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload) VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(sql, event.getId(), event.getAggregateType(), event.getAggregateId(), event.getEventType(), event.getTopic(), event.getPayload());
    }

    public List<OutboxEvent> findAndLockUnprocessedEvents(int limit) {
        String sql = """
            SELECT * FROM outbox_events
            ORDER BY created_at
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
        return jdbcTemplate.query(sql, rowMapper, limit);
    }

    public void deleteByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String sql = String.format(
                "DELETE FROM outbox_events WHERE id IN (%s)",
                String.join(",", java.util.Collections.nCopies(ids.size(), "?"))
        );
        jdbcTemplate.update(sql, ids.toArray());
    }
}