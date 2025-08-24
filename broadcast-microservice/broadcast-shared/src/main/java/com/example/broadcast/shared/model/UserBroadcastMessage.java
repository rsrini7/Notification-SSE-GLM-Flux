package com.example.broadcast.shared.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * Entity representing user-specific broadcast message tracking
 * This tracks delivery and read status for each user per broadcast
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserBroadcastMessage{
    private Long id;
    private Long broadcastId;
    private String userId;
    private String deliveryStatus; // PENDING, DELIVERED, FAILED
    private String readStatus; // UNREAD, READ
    private ZonedDateTime deliveredAt;
    private ZonedDateTime readAt;
    private ZonedDateTime createdAt;
    private ZonedDateTime updatedAt;
}