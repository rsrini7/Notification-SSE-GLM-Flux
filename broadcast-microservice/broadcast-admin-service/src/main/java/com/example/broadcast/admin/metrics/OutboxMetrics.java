package com.example.broadcast.admin.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Exports critical metrics related to the outbox table to Prometheus.
 */
@Component
@RequiredArgsConstructor
public class OutboxMetrics implements MeterBinder {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("broadcast.outbox.size", this, value -> getOutboxSize())
            .description("The current number of unprocessed events in the outbox table.")
            .register(registry);
    }

    private double getOutboxSize() {
        // Use a simple, fast query to get the current count.
        String sql = "SELECT COUNT(*) FROM outbox_events";
        Long count = jdbcTemplate.queryForObject(sql, Long.class);
        return count != null ? count.doubleValue() : 0.0;
    }
}