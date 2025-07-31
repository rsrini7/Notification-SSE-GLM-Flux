package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.ZonedDateTime;

/**
 * DTO for user-specific broadcast message responses
 * Used to return user-specific broadcast information including delivery status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBroadcastResponse {
    private Long id;
    private Long broadcastId;
    private String userId;
    private String deliveryStatus;
    private String readStatus;
    private ZonedDateTime deliveredAt;
    private ZonedDateTime readAt;
    private ZonedDateTime createdAt;
    
    // Embedded broadcast information
    private String senderName;
    private String content;
    private String priority;
    private String category;
    private ZonedDateTime broadcastCreatedAt;
    private ZonedDateTime scheduledAt;    
    private ZonedDateTime expiresAt;
}
