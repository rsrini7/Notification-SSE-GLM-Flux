package com.example.broadcast.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.ZonedDateTime;

@Data
@Builder(toBuilder = true) // This enables the toBuilder() method
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
    @Builder.Default
    private boolean isFireAndForget = false;
}