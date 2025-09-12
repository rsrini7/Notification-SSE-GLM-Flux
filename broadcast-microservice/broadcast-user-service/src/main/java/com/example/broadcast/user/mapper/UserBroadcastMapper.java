package com.example.broadcast.user.mapper;

// Import your user DTOs after moving them
import com.example.broadcast.user.dto.*;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.util.Constants;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Mapper(componentModel = "spring")
public abstract class UserBroadcastMapper {

    // Helper method from original mapper
    protected OffsetDateTime epochMilliToDateTime(long epochMilli) {
        if (epochMilli == 0L) return null;
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC);
    }
    
    @Mapping(source = "broadcast.id", target = "broadcastId")
    @Mapping(source = "broadcast.senderName", target = "senderName")
    @Mapping(source = "broadcast.content", target = "content")
    @Mapping(source = "broadcast.priority", target = "priority")
    @Mapping(source = "broadcast.category", target = "category")
    @Mapping(source = "broadcast.createdAt", target = "broadcastCreatedAt")
    @Mapping(source = "broadcast.expiresAt", target = "expiresAt")
    @Mapping(source = "broadcast.scheduledAt", target = "scheduledAt")
    // Target fields are set in the @AfterMapping method
    @Mapping(target = "userMessageId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "deliveryStatus", ignore = true)
    @Mapping(target = "readStatus", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    @Mapping(target = "readAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    public abstract UserBroadcastResponse toUserBroadcastResponseFromEntity(UserBroadcastMessage message, BroadcastMessage broadcast);

    @AfterMapping
    protected void handleNullableMessageSource(@MappingTarget UserBroadcastResponse.UserBroadcastResponseBuilder builder, UserBroadcastMessage message, BroadcastMessage broadcast) {
        if (message != null) {
            builder.userMessageId(message.getId())
                   .userId(message.getUserId())
                   .deliveryStatus(message.getDeliveryStatus())
                   .readStatus(message.getReadStatus())
                   .deliveredAt(message.getDeliveredAt())
                   .readAt(message.getReadAt())
                   .createdAt(message.getCreatedAt());
        } else {
            builder.deliveryStatus(Constants.DeliveryStatus.DELIVERED.name())
                   .readStatus(Constants.ReadStatus.UNREAD.name())
                   .createdAt(broadcast.getCreatedAt());
        }
    }

    @Mapping(source = "broadcast.id", target = "broadcastId")
    @Mapping(source = "broadcast.senderName", target = "senderName")
    @Mapping(source = "broadcast.content", target = "content")
    @Mapping(source = "broadcast.priority", target = "priority")
    @Mapping(source = "broadcast.category", target = "category")
    @Mapping(source = "broadcast.createdAt", target = "broadcastCreatedAt")
    @Mapping(source = "broadcast.expiresAt", target = "expiresAt")
    @Mapping(source = "userMessage.messageId", target = "userMessageId")
    @Mapping(source = "userMessage.deliveryStatus", target = "deliveryStatus")
    @Mapping(source = "userMessage.readStatus", target = "readStatus")
    @Mapping(source = "userMessage.createdAtEpochMilli", target = "createdAt")
    @Mapping(target = "userId", ignore = true) // Not available in this context
    @Mapping(target = "deliveredAt", ignore = true) // Not available in cache DTO
    @Mapping(target = "readAt", ignore = true) // Not available in cache DTO
    public abstract UserBroadcastResponse toUserBroadcastResponseFromCache(UserMessageInbox userMessage, BroadcastMessage broadcast);
}