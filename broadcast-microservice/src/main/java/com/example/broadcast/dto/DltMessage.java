package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * DTO representing a message from the Dead Letter Topic (DLT).
 * It holds the failed message's payload and metadata about the failure.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DltMessage {
    private String id;
    private String originalTopic;
    private int originalPartition;
    private long originalOffset;
    private String exceptionMessage;
    private String exceptionStackTrace;
    private ZonedDateTime failedAt;
    private String originalMessagePayload; // The payload as a JSON string
}