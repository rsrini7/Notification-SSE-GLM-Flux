package com.example.broadcast.admin.mapper;

import com.example.broadcast.admin.dto.*;
import com.example.broadcast.admin.model.DltMessage;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.util.JsonUtils;
import org.mapstruct.*;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Mapper(componentModel = "spring", imports = { OffsetDateTime.class, ZoneOffset.class, UUID.class, JsonUtils.class })
public interface AdminBroadcastMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "createdAt", expression = "java(OffsetDateTime.now(ZoneOffset.UTC))")
    @Mapping(target = "updatedAt", expression = "java(OffsetDateTime.now(ZoneOffset.UTC))")
    @Mapping(source = "fireAndForget", target = "fireAndForget")
    @Mapping(target = "targetIds", expression = "java(JsonUtils.toJsonArray(request.getTargetIds()))")
    @Mapping(source = "correlationId", target = "correlationId")
    BroadcastMessage toBroadcastMessage(BroadcastRequest request);

    @Mapping(target = "targetIds", expression = "java(JsonUtils.parseJsonArray(broadcast.getTargetIds()))")
    @Mapping(source = "broadcast.correlationId", target = "correlationId")
    @Mapping(target = "totalDelivered", ignore = true)
    @Mapping(target = "totalRead", ignore = true)
    BroadcastResponse toBroadcastResponse(BroadcastMessage broadcast, int totalTargeted);
    
    @Mapping(source = "id", target = "broadcastId")
    @Mapping(target = "deliveryRate", ignore = true)
    @Mapping(target = "readRate", ignore = true)
    BroadcastStatsResponse toBroadcastStatsResponse(BroadcastResponse response);

    @AfterMapping
    default void calculateRates(@MappingTarget BroadcastStatsResponse stats, BroadcastResponse source) {
        double deliveryRate = (source.getTotalTargeted() != null && source.getTotalTargeted() > 0) ? (double) source.getTotalDelivered() / source.getTotalTargeted() : 0.0;
        double readRate = (source.getTotalDelivered() != null && source.getTotalDelivered() > 0) ? (double) source.getTotalRead() / source.getTotalDelivered() : 0.0;
        stats.setDeliveryRate(deliveryRate);
        stats.setReadRate(readRate);
    }

    @Mapping(source = "key", target = "originalKey")
    @Mapping(source = "failedEvent.broadcastId", target = "broadcastId")
    @Mapping(source = "payloadJson", target = "originalMessagePayload")
    @Mapping(source = "displayTitle", target = "exceptionMessage")
    @Mapping(target = "id", expression = "java(UUID.randomUUID().toString())")
    @Mapping(target = "failedAt", expression = "java(OffsetDateTime.now(ZoneOffset.UTC))")
    @Mapping(source = "failedEvent.correlationId", target = "correlationId")
    DltMessage toDltMessage(MessageDeliveryEvent failedEvent, String key, String originalTopic,
                            Integer originalPartition, Long originalOffset,
                            String displayTitle, String exceptionStackTrace, String payloadJson);
}