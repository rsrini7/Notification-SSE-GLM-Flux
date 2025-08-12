package com.example.broadcast.shared.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DltMessage {
    private String id;
    private String originalKey; // ADD THIS FIELD
    private String originalTopic;
    private int originalPartition;
    private long originalOffset;
    private String exceptionMessage;
    private String exceptionStackTrace;
    private ZonedDateTime failedAt;
    private String originalMessagePayload; // The payload as a JSON string
}