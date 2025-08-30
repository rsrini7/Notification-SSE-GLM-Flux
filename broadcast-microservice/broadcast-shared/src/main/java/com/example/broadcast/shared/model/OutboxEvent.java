package com.example.broadcast.shared.model;

import lombok.Builder;
import lombok.Data;

import org.springframework.data.annotation.Transient;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@Table("outbox_events")
public class OutboxEvent implements Persistable<UUID> {
    @Id
    private UUID id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String payload;
    private OffsetDateTime createdAt;
    private String topic;

    @Override
    @Transient // This ensures Spring Data does not try to persist this field
    public boolean isNew() {
        // This forces Spring Data to always perform an INSERT for OutboxEvent
        return true; 
    }
}