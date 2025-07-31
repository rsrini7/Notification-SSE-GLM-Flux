package com.example.broadcast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Entity representing a broadcast message created by an administrator
 * This is the admin-side record that stores permanent broadcast information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@With
public class BroadcastMessage {
    private Long id;
    private String senderId;
    private String senderName;
    private String content;
    private String targetType; // ALL, SELECTED, ROLE
    private List<String> targetIds; // JSON array of user IDs or role IDs
    private String priority; // LOW, NORMAL, HIGH, URGENT
    private String category;
    private LocalDateTime scheduledAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String status; // ACTIVE, EXPIRED, CANCELLED
}