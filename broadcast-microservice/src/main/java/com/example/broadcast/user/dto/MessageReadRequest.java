package com.example.broadcast.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;

/**
 * DTO for marking messages as read
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageReadRequest {
    @NotNull(message = "User ID is required")
    private String userId;
    
    @NotNull(message = "Message ID is required")
    private Long messageId;
    
    @Builder.Default
    private Boolean markAsRead = true;
}