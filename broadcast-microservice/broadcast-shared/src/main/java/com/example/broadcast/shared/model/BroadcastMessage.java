package com.example.broadcast.shared.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity representing a broadcast message created by an administrator
 * This is the admin-side record that stores permanent broadcast information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@With
@Table("broadcast_messages")
public class BroadcastMessage {
    @Id
    private Long id;
    private String senderId;
    private String senderName;
    private String content;
    private String targetType;
    private String targetIds;
    private String priority;
    private String category;
    private OffsetDateTime scheduledAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String status;
    @Builder.Default
    private boolean fireAndForget = false;
}