package com.example.broadcast.shared.mapper;

import com.example.broadcast.shared.dto.admin.BroadcastResponse;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.dto.admin.BroadcastStatsResponse;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import com.example.broadcast.shared.dto.admin.BroadcastRequest;
import com.example.broadcast.shared.util.Constants;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * A dedicated mapper component to handle conversions between Broadcast entities and DTOs.
 * Implementation is generated at compile time by MapStruct.
 */
@Mapper(componentModel = "spring", imports = { ZonedDateTime.class, ZoneOffset.class, UUID.class })
public interface BroadcastMapper {

    @Mapping(target = "totalDelivered", constant = "0")
    @Mapping(target = "totalRead", constant = "0")
    BroadcastResponse toBroadcastResponse(BroadcastMessage broadcast, int totalTargeted);

    // This is the primary mapping. It handles fields that are ALWAYS populated from the 'broadcast' object.
    @Mapping(source = "broadcast.id", target = "broadcastId")
    @Mapping(source = "broadcast.senderName", target = "senderName")
    @Mapping(source = "broadcast.content", target = "content")
    @Mapping(source = "broadcast.priority", target = "priority")
    @Mapping(source = "broadcast.category", target = "category")
    @Mapping(source = "broadcast.createdAt", target = "broadcastCreatedAt")
    @Mapping(source = "broadcast.expiresAt", target = "expiresAt")
    @Mapping(source = "broadcast.scheduledAt", target = "scheduledAt")
    // Fields from 'message' will be handled in the @AfterMapping method below
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "deliveryStatus", ignore = true)
    @Mapping(target = "readStatus", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    @Mapping(target = "readAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    UserBroadcastResponse toUserBroadcastResponseFromEntity(UserBroadcastMessage message, BroadcastMessage broadcast);

    // This method is automatically called by MapStruct after the initial mapping above.
    // It contains your original null-check logic.
    @AfterMapping
    default void handleNullableMessageSource(@MappingTarget UserBroadcastResponse.UserBroadcastResponseBuilder builder,
                                             UserBroadcastMessage message, BroadcastMessage broadcast) {
        if (message != null) {
            builder.id(message.getId())
                   .userId(message.getUserId())
                   .deliveryStatus(message.getDeliveryStatus())
                   .readStatus(message.getReadStatus())
                   .deliveredAt(message.getDeliveredAt())
                   .readAt(message.getReadAt())
                   .createdAt(message.getCreatedAt());
        } else {
            // This handles the fan-out-on-read case where a transient message is created
            builder.id(broadcast.getId()) // Use broadcast ID as a transient ID for the event
                   .deliveryStatus(Constants.DeliveryStatus.DELIVERED.name())
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
    @Mapping(source = "userMessage.messageId", target = "id")
    @Mapping(source = "userMessage.deliveryStatus", target = "deliveryStatus")
    @Mapping(source = "userMessage.readStatus", target = "readStatus")
    @Mapping(source = "userMessage.createdAt", target = "createdAt")
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    @Mapping(target = "readAt", ignore = true)
    UserBroadcastResponse toUserBroadcastResponseFromCache(UserMessageInbox userMessage, BroadcastMessage broadcast);

    @Mapping(source = "id", target = "messageId")
    UserMessageInbox toUserMessageInbox(UserBroadcastMessage message);

    /**
     * Maps a BroadcastRequest DTO to a BroadcastMessage model.
     * Generates creation and update timestamps during the mapping process.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", expression = "java(ZonedDateTime.now(ZoneOffset.UTC))")
    @Mapping(target = "updatedAt", expression = "java(ZonedDateTime.now(ZoneOffset.UTC))")
    @Mapping(source = "fireAndForget", target = "isFireAndForget") 
    BroadcastMessage toBroadcastMessage(BroadcastRequest request);


    @Mapping(source = "id", target = "broadcastId")
    @Mapping(target = "deliveryRate", ignore = true)
    @Mapping(target = "readRate", ignore = true)
    BroadcastStatsResponse toBroadcastStatsResponse(BroadcastResponse response);

    @AfterMapping
    default void calculateRates(@MappingTarget BroadcastStatsResponse stats, BroadcastResponse source) {
        double deliveryRate = (source.getTotalTargeted() != null && source.getTotalTargeted() > 0)
            ? (double) source.getTotalDelivered() / source.getTotalTargeted() : 0.0;

        double readRate = (source.getTotalDelivered() != null && source.getTotalDelivered() > 0)
            ? (double) source.getTotalRead() / source.getTotalDelivered() : 0.0;

        stats.setDeliveryRate(deliveryRate);
        stats.setReadRate(readRate);
    }
}