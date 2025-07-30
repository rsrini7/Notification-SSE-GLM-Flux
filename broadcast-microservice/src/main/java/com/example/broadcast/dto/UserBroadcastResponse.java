package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

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
    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    
    // Embedded broadcast information
    private String senderName;
    private String content;
    private String priority;
    private String category;
    private LocalDateTime broadcastCreatedAt;
}