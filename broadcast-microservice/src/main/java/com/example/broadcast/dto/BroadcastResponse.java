package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * DTO for broadcast message responses
 * Used to return broadcast information to clients
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastResponse {
    private Long id;
    private String senderId;
    private String senderName;
    private String content;
    private String targetType;
    private List<String> targetIds;
    private String priority;
    private String category;
    private ZonedDateTime expiresAt;
    private ZonedDateTime createdAt;
    private String status;
    private Integer totalTargeted;
    private Integer totalDelivered;
    private Integer totalRead;
}