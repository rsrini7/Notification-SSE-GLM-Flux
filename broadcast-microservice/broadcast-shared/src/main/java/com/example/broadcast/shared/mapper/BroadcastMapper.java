package com.example.broadcast.shared.mapper;

import com.example.broadcast.shared.dto.admin.BroadcastResponse;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.dto.admin.BroadcastStatsResponse;
import com.example.broadcast.shared.dto.admin.DltMessage;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import com.example.broadcast.shared.dto.admin.BroadcastRequest;
import com.example.broadcast.shared.dto.BroadcastContent;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = { OffsetDateTime.class, ZoneOffset.class, UUID.class, JsonUtils.class })
public abstract class BroadcastMapper {

    @Autowired
    public ObjectMapper objectMapper;

    public static final Logger log = LoggerFactory.getLogger(BroadcastMapper.class);
    
    // --- Helper methods for conversion ---
    protected long dateTimeToEpochMilli(OffsetDateTime dateTime) {
        if (dateTime == null) return 0L;
        return dateTime.toInstant().toEpochMilli();
    }

    protected OffsetDateTime epochMilliToDateTime(long epochMilli) {
        if (epochMilli == 0L) return null;
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC);
    }

    @Mapping(target = "totalDelivered", constant = "0")
    @Mapping(target = "totalRead", constant = "0")
    @Mapping(target = "targetIds", expression = "java(JsonUtils.parseJsonArray(broadcast.getTargetIds()))")
    public abstract BroadcastResponse toBroadcastResponse(BroadcastMessage broadcast, int totalTargeted);

    @Mapping(source = "broadcast.id", target = "broadcastId")
    @Mapping(source = "broadcast.senderName", target = "senderName")
    @Mapping(source = "broadcast.content", target = "content")
    @Mapping(source = "broadcast.priority", target = "priority")
    @Mapping(source = "broadcast.category", target = "category")
    @Mapping(source = "broadcast.createdAt", target = "broadcastCreatedAt")
    @Mapping(source = "broadcast.expiresAt", target = "expiresAt")
    @Mapping(source = "broadcast.scheduledAt", target = "scheduledAt")
    @Mapping(target = "userMessageId", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "deliveryStatus", ignore = true)
    @Mapping(target = "readStatus", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    @Mapping(target = "readAt", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    public abstract UserBroadcastResponse toUserBroadcastResponseFromEntity(UserBroadcastMessage message, BroadcastMessage broadcast);

    @AfterMapping
    protected void handleNullableMessageSource(@MappingTarget UserBroadcastResponse.UserBroadcastResponseBuilder builder,
                                             UserBroadcastMessage message, BroadcastMessage broadcast) {
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
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "deliveredAt", ignore = true)
    @Mapping(target = "readAt", ignore = true)
    public abstract UserBroadcastResponse toUserBroadcastResponseFromCache(UserMessageInbox userMessage, BroadcastMessage broadcast);

    @Mapping(source = "id", target = "messageId")
    @Mapping(source = "createdAt", target = "createdAtEpochMilli")
    public abstract UserMessageInbox toUserMessageInbox(UserBroadcastMessage message);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", expression = "java(OffsetDateTime.now(ZoneOffset.UTC))")
    @Mapping(target = "updatedAt", expression = "java(OffsetDateTime.now(ZoneOffset.UTC))")
    @Mapping(source = "fireAndForget", target = "isFireAndForget") 
    @Mapping(target = "targetIds", expression = "java(JsonUtils.toJsonArray(request.getTargetIds()))")
    public abstract BroadcastMessage toBroadcastMessage(BroadcastRequest request);

    @Mapping(source = "id", target = "broadcastId")
    @Mapping(target = "deliveryRate", ignore = true)
    @Mapping(target = "readRate", ignore = true)
    public abstract BroadcastStatsResponse toBroadcastStatsResponse(BroadcastResponse response);

    @AfterMapping
    protected void calculateRates(@MappingTarget BroadcastStatsResponse stats, BroadcastResponse source) {
        double deliveryRate = (source.getTotalTargeted() != null && source.getTotalTargeted() > 0)
             ? (double) source.getTotalDelivered() / source.getTotalTargeted() : 0.0;
        double readRate = (source.getTotalDelivered() != null && source.getTotalDelivered() > 0)
            ? (double) source.getTotalRead() / source.getTotalDelivered() : 0.0;
        stats.setDeliveryRate(deliveryRate);
        stats.setReadRate(readRate);
    }

    @Mapping(source = "broadcast.id", target = "broadcastId")
    @Mapping(source = "message", target = "message")
    @Mapping(source = "eventType", target = "eventType")
    @Mapping(target = "eventId", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "timestampEpochMilli", expression = "java(System.currentTimeMillis())")
    @Mapping(source = "broadcast.fireAndForget", target = "isFireAndForget")
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "errorDetails", ignore = true)
    public abstract MessageDeliveryEvent toMessageDeliveryEvent(BroadcastMessage broadcast, String eventType, String message);

    @Mapping(source = "eventPayload.eventId", target = "id")
    @Mapping(target = "aggregateType", expression = "java(eventPayload.getClass().getSimpleName())")
    @Mapping(source = "aggregateId", target = "aggregateId")
    @Mapping(source = "eventPayload.eventType", target = "eventType")
    @Mapping(source = "topicName", target = "topic")
    @Mapping(source = "eventPayload.timestampEpochMilli", target = "createdAt")
    @Mapping(target = "payload", ignore = true)
    public abstract OutboxEvent toOutboxEvent(MessageDeliveryEvent eventPayload, String topicName, String aggregateId);

    @AfterMapping
    protected void afterToOutboxEvent(MessageDeliveryEvent eventPayload, String aggregateId, @MappingTarget OutboxEvent.OutboxEventBuilder builder) {
        try {
            String payloadJson = objectMapper.writeValueAsString(eventPayload);
            builder.payload(payloadJson);
        } catch (JsonProcessingException e) {
            log.error("Critical: Failed to serialize event payload for outbox for aggregateId {}.", aggregateId, e);
            throw new RuntimeException("Failed to serialize event payload for outbox.", e);
        }
    }

    @Mapping(source = "key", target = "originalKey")
    @Mapping(source = "failedEvent.broadcastId", target = "broadcastId")
    @Mapping(source = "payloadJson", target = "originalMessagePayload")
    @Mapping(source = "displayTitle", target = "exceptionMessage")
    @Mapping(target = "id", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "failedAt", expression = "java(OffsetDateTime.now(ZoneOffset.UTC))")
    public abstract DltMessage toDltMessage(MessageDeliveryEvent failedEvent, String key, String originalTopic,
                                        Integer originalPartition, Long originalOffset,
                                        String displayTitle, String exceptionStackTrace, String payloadJson);

    @Mapping(source = "fireAndForget", target = "isFireAndForget")
    @Mapping(target = "targetIds", expression = "java(JsonUtils.parseJsonArray(entity.getTargetIds()))")
    @Mapping(source = "scheduledAt", target = "scheduledAtEpochMilli")
    @Mapping(source = "expiresAt", target = "expiresAtEpochMilli")
    @Mapping(source = "createdAt", target = "createdAtEpochMilli")
    @Mapping(source = "updatedAt", target = "updatedAtEpochMilli")
    public abstract BroadcastContent toBroadcastContentDTO(BroadcastMessage entity);
    
    @Mapping(source = "fireAndForget", target = "isFireAndForget")
    @Mapping(target = "targetIds", expression = "java(JsonUtils.toJsonArray(dto.getTargetIds()))")
    @Mapping(source = "scheduledAtEpochMilli", target = "scheduledAt")
    @Mapping(source = "expiresAtEpochMilli", target = "expiresAt")
    @Mapping(source = "createdAtEpochMilli", target = "createdAt")
    @Mapping(source = "updatedAtEpochMilli", target = "updatedAt")
    public abstract BroadcastMessage toBroadcastMessage(BroadcastContent dto);
}