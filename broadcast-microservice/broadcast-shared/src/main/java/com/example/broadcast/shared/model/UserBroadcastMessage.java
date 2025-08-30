package com.example.broadcast.shared.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity representing user-specific broadcast message tracking
 * This tracks delivery and read status for each user per broadcast
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_broadcast_messages")
public class UserBroadcastMessage{
    @Id
    private Long id;
    private Long broadcastId;
    private String userId;
    private String deliveryStatus; // PENDING, DELIVERED, FAILED
    private String readStatus; // UNREAD, READ
    private OffsetDateTime deliveredAt;
    private OffsetDateTime readAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}