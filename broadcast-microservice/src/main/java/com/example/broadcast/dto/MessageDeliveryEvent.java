package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.ZonedDateTime;

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
    // START OF CHANGE: Add a dedicated flag for transient failure testing
    @Builder.Default
    private boolean transientFailure = false;
    // END OF CHANGE
}