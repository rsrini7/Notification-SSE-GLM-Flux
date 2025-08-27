package com.example.broadcast.shared.mapper;

import com.example.broadcast.shared.dto.admin.BroadcastResponse;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
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
    public UserBroadcastResponse toUserBroadcastResponseFromEntity(UserBroadcastMessage message, BroadcastMessage broadcast) {
        UserBroadcastResponse.UserBroadcastResponseBuilder builder = UserBroadcastResponse.builder()
                .broadcastId(broadcast.getId())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .broadcastCreatedAt(broadcast.getCreatedAt())
                .expiresAt(broadcast.getExpiresAt());

        if (message != null) {
            builder.id(message.getId())
                   .userId(message.getUserId())
                   .deliveryStatus(message.getDeliveryStatus())
                   .readStatus(message.getReadStatus())
                   .deliveredAt(message.getDeliveredAt())
                   .readAt(message.getReadAt())
                   .createdAt(message.getCreatedAt());
        } else {
            builder.id(broadcast.getId())
                   .deliveryStatus("DELIVERED")
                   .readStatus("UNREAD")
                   .createdAt(broadcast.getCreatedAt());
        }

        return builder.build();
    }

    /**
     * Maps a lightweight cached message and a full broadcast message to a user-facing response DTO.
     * @param userMessage The cached, user-specific status information from UserMessageInbox.
     * @param broadcast The full broadcast content.
     * @return The complete response DTO for the UI.
     */
    public UserBroadcastResponse toUserBroadcastResponseFromCache(UserMessageInbox userMessage, BroadcastMessage broadcast) {
        return UserBroadcastResponse.builder()
                .id(userMessage.getMessageId())
                .broadcastId(broadcast.getId())
                .userId(null) // Not needed in the final UI response DTO
                .deliveryStatus(userMessage.getDeliveryStatus())
                .readStatus(userMessage.getReadStatus())
                .createdAt(userMessage.getCreatedAt())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .broadcastCreatedAt(broadcast.getCreatedAt())
                .expiresAt(broadcast.getExpiresAt())
                .build();
    }

    /**
     * Maps a database entity to the lightweight cache DTO.
     * @param message The UserBroadcastMessage entity from the database.
     * @return The lightweight DTO for caching.
     */
    public UserMessageInbox toUserMessageInbox(UserBroadcastMessage message) {
        return new UserMessageInbox(
                message.getId(),
                message.getBroadcastId(),
                message.getDeliveryStatus(),
                message.getReadStatus(),
                message.getCreatedAt()
        );
    }
}