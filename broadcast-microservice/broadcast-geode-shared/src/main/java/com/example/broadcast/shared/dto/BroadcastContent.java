package com.example.broadcast.shared.dto; 

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

// This is now a pure DTO for Geode.
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastContent implements Serializable {
    private Long id;
    private String correlationId;
    private String senderId;
    private String senderName;
    private String content;
    private String targetType;  // ALL, SELECTED, ROLE
    private List<String> targetIds;
    private String priority;
    private String category;
    private long scheduledAtEpochMilli;
    private long expiresAtEpochMilli;
    private long createdAtEpochMilli;
    private long updatedAtEpochMilli;
    private String status; // ACTIVE, EXPIRED, CANCELLED
    private boolean fireAndForget;
}