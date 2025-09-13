package com.example.broadcast.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("dlt_messages")
public class DltMessage implements Persistable<String> {
    @Id
    private String id;
    private Long broadcastId;
    private String correlationId;
    private String originalKey; // ADD THIS FIELD
    private String originalTopic;
    private int originalPartition;
    private long originalOffset;
    private String exceptionMessage;
    private String exceptionStackTrace;
    private OffsetDateTime failedAt;
    private String originalMessagePayload; // The payload as a JSON string

    @Override
    @Transient
    public boolean isNew() {
        // This forces an INSERT every time for DLT messages.
        return true;
    }
}