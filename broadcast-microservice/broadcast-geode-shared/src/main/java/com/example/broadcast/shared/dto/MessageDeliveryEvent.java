package com.example.broadcast.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;
import java.io.Serializable;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MessageDeliveryEvent implements Serializable {
    private String eventId;
    private Long broadcastId;
    private String userId;
    private String eventType; // CREATED, DELIVERED, READ, FAILED
    private OffsetDateTime timestamp;
    private String message;
    private String errorDetails;
    @Builder.Default
    private boolean isFireAndForget = false;
}