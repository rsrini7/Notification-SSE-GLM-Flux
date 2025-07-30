package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for user message responses
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserMessageResponse {
    private Long id;
    private Long broadcastId;
    private String senderId;
    private String senderName;
    private String content;
    private String priority;
    private String category;
    private String deliveryStatus;
    private String readStatus;
    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}