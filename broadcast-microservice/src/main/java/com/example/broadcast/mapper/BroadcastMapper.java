package com.example.broadcast.mapper;

import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import org.springframework.stereotype.Component;

/**
 * A dedicated mapper component to handle conversions between Broadcast entities and DTOs.
 * This centralizes mapping logic, making it consistent and easier to maintain.
 */
@Component
public class BroadcastMapper {

    /**
     * Maps a BroadcastMessage entity to a BroadcastResponse DTO.
     * @param broadcast The entity to map.
     * @param totalTargeted The calculated number of targeted users.
     * @return The response DTO.
     */
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

    /**
     * Maps a UserBroadcastMessage and its parent BroadcastMessage to a UserBroadcastResponse DTO.
     * @param message The user-specific message entity.
     * @param broadcast The parent broadcast entity.
     * @return The user-specific response DTO.
     */
    public UserBroadcastResponse toUserBroadcastResponse(UserBroadcastMessage message, BroadcastMessage broadcast) {
        return UserBroadcastResponse.builder()
                .id(message.getId())
                .broadcastId(message.getBroadcastId())
                .userId(message.getUserId())
                .deliveryStatus(message.getDeliveryStatus())
                .readStatus(message.getReadStatus())
                .deliveredAt(message.getDeliveredAt())
                .readAt(message.getReadAt())
                .createdAt(message.getCreatedAt())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .broadcastCreatedAt(broadcast.getCreatedAt())
                .expiresAt(broadcast.getExpiresAt())
                .build();
    }
}