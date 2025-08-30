package com.example.broadcast.shared.dto; // This package is fine to reuse

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.List;

// This is now a pure DTO for Geode.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastContent implements Serializable {
    private Long id;
    private String senderId;
    private String senderName;
    private String content;
    private String targetType;  // ALL, SELECTED, ROLE
    private List<String> targetIds;
    private String priority;
    private String category;
    private OffsetDateTime scheduledAt;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String status; // ACTIVE, EXPIRED, CANCELLED
    private boolean isFireAndForget;
}