package com.example.broadcast.mapper;

import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.model.BroadcastMessage;
import org.springframework.stereotype.Component;

/**
 * A dedicated mapper component to handle conversions between Broadcast entities and DTOs.
 * This centralizes mapping logic, making it consistent and easier to maintain.
 */
@Component
public class BroadcastMapper {

    public BroadcastResponse toBroadcastResponse(BroadcastMessage broadcast, int totalTargeted) {
        return BroadcastResponse.builder()
                .id(broadcast.getId())
                .senderId(broadcast.getSenderId())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .targetType(broadcast.getTargetType()) 
                .targetIds(broadcast.getTargetIds())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .expiresAt(broadcast.getExpiresAt())
                .createdAt(broadcast.getCreatedAt())
                .scheduledAt(broadcast.getScheduledAt())
                .status(broadcast.getStatus()) 
                .totalTargeted(totalTargeted)
                .totalDelivered(0) // Initial response has 0 delivered
                .totalRead(0)      // Initial response has 0 read
                .build();
    }
}