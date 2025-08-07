package com.example.broadcast.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * DTO for broadcast message creation requests
 * Used by administrators to create new broadcasts
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastRequest {
    @NotBlank(message = "Sender ID is required")
    private String senderId;
    
    @NotBlank(message = "Sender name is required")
    private String senderName;
    
    @NotBlank(message = "Content is required")
    private String content;
    
    @NotNull(message = "Target type is required")
    private String targetType; // ALL, SELECTED, ROLE
    
    private List<String> targetIds; // Required when targetType is SELECTED or ROLE
    
    @Builder.Default
    private String priority = "NORMAL"; // LOW, NORMAL, HIGH, URGENT
    
    private String category;
    
    private java.time.ZonedDateTime scheduledAt;

    private java.time.ZonedDateTime expiresAt;
    
    @Builder.Default
    private boolean isImmediate = true;
    
    @Builder.Default
    private boolean isFireAndForget = false;

}