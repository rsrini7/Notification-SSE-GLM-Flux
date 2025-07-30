package com.example.broadcast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity representing user-specific broadcast message tracking
 * This tracks delivery and read status for each user per broadcast
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBroadcastMessage {
    private Long id;
    private Long broadcastId;
    private String userId;
    private String deliveryStatus; // PENDING, DELIVERED, FAILED
    private String readStatus; // UNREAD, READ, ARCHIVED
    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}