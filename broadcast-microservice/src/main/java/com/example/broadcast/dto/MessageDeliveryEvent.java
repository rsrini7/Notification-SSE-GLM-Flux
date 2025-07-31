package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * DTO for message delivery events
 * Used for Kafka event streaming and SSE communication
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageDeliveryEvent {
    private String eventId;
    private Long broadcastId;
    private String userId;
    private String eventType; // CREATED, DELIVERED, READ, FAILED
    private String podId;
    private ZonedDateTime timestamp;
    private String message;
    private String errorDetails;
}