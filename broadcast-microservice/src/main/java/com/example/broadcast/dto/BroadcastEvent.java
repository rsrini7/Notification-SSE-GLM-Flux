package com.example.broadcast.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

import java.util.List;

/**
 * Kafka event for broadcast messaging
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastEvent {
    private String eventId;
    private String eventType; // BROADCAST_CREATED, MESSAGE_DELIVERED, MESSAGE_READ
    private Long broadcastId;
    private String senderId;
    private String content;
    private String targetType;
    private List<String> targetIds;
    private String priority;
    private String category;
    private ZonedDateTime createdAt;
    private String podId;
    private Integer retryCount;
}