package com.example.broadcast.shared.mapper;

import com.example.broadcast.shared.dto.BroadcastContent;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.util.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = { UUID.class, JsonUtils.class })
@Slf4j
public abstract class SharedEventMapper {

    @Autowired
    public ObjectMapper objectMapper;

    // Helper methods for Geode DTOs (epoch conversion)
    protected long dateTimeToEpochMilli(OffsetDateTime dateTime) {
        if (dateTime == null) return 0L;
        return dateTime.toInstant().toEpochMilli();
    }

    protected OffsetDateTime epochMilliToDateTime(long epochMilli) {
        if (epochMilli == 0L) return null;
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC);
    }

    // --- Mappings ---

    @Mapping(source = "broadcast.id", target = "broadcastId")
    @Mapping(target = "eventId", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "timestampEpochMilli", expression = "java(System.currentTimeMillis())")
    @Mapping(source = "broadcast.fireAndForget", target = "fireAndForget")
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "errorDetails", ignore = true)
    public abstract MessageDeliveryEvent toMessageDeliveryEvent(BroadcastMessage broadcast, String eventType, String message);

    @Mapping(source = "eventPayload.eventId", target = "id")
    @Mapping(target = "aggregateType", expression = "java(eventPayload.getClass().getSimpleName())")
    @Mapping(source = "aggregateId", target = "aggregateId")
    @Mapping(source = "eventPayload.eventType", target = "eventType")
    @Mapping(source = "topicName", target = "topic")
    @Mapping(target = "createdAt", source = "eventPayload.timestampEpochMilli")
    @Mapping(target = "payload", ignore = true)
    public abstract OutboxEvent toOutboxEvent(MessageDeliveryEvent eventPayload, String topicName, String aggregateId);

    @AfterMapping
    protected void afterToOutboxEvent(MessageDeliveryEvent eventPayload, String aggregateId, @MappingTarget OutboxEvent.OutboxEventBuilder builder) {
        try {
            builder.payload(objectMapper.writeValueAsString(eventPayload));
        } catch (JsonProcessingException e) {
            log.error("Critical: Failed to serialize event payload for outbox for aggregateId {}.", aggregateId, e);
            throw new RuntimeException("Failed to serialize event payload for outbox.", e);
        }
    }

    @Mapping(source = "fireAndForget", target = "fireAndForget")
    @Mapping(target = "targetIds", expression = "java(JsonUtils.parseJsonArray(entity.getTargetIds()))")
    @Mapping(source = "scheduledAt", target = "scheduledAtEpochMilli")
    @Mapping(source = "expiresAt", target = "expiresAtEpochMilli")
    @Mapping(source = "createdAt", target = "createdAtEpochMilli")
    @Mapping(source = "updatedAt", target = "updatedAtEpochMilli")
    public abstract BroadcastContent toBroadcastContentDTO(BroadcastMessage entity);
    
    @Mapping(source = "fireAndForget", target = "fireAndForget")
    @Mapping(target = "targetIds", expression = "java(JsonUtils.toJsonArray(dto.getTargetIds()))")
    @Mapping(source = "scheduledAtEpochMilli", target = "scheduledAt")
    @Mapping(source = "expiresAtEpochMilli", target = "expiresAt")
    @Mapping(source = "createdAtEpochMilli", target = "createdAt")
    @Mapping(source = "updatedAtEpochMilli", target = "updatedAt")
    public abstract BroadcastMessage toBroadcastMessage(BroadcastContent dto);
}