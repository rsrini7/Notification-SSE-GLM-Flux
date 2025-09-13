package com.example.broadcast.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

/**
 * DTO for user-specific broadcast message responses
 * Used to return user-specific broadcast information including delivery status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBroadcastResponse {
    private String correlationId;
    private Long userMessageId;
    private Long broadcastId;
    private String userId;
    private String deliveryStatus;
    private String readStatus;
    private OffsetDateTime deliveredAt;
    private OffsetDateTime readAt;
    private OffsetDateTime createdAt;
    private String senderName;
    private String content;
    private String priority;
    private String category;
    private OffsetDateTime broadcastCreatedAt;
    private OffsetDateTime scheduledAt;    
    private OffsetDateTime expiresAt;
}
