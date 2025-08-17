package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.service.cache.CacheService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaBroadcastOrchestratorService {

    private final UserBroadcastTargetRepository userBroadcastTargetRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;

    @KafkaListener(
        topics = "${broadcast.kafka.topic.name-orchestration}",
        groupId = "${broadcast.kafka.consumer.group-orchestration}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void orchestrateBroadcastEvents(MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("Orchestration event received for type '{}' on broadcast ID: {}. [Topic: {}, Partition: {}, Offset: {}]", event.getEventType(), event.getBroadcastId(), topic, partition, offset);

        final List<String> targetUsers;
        
        // If the event is user-specific, target only that user.
        if (Constants.EventType.READ.name().equals(event.getEventType())) {
            log.debug("Handling user-specific READ event for user: {}", event.getUserId());
            targetUsers = List.of(event.getUserId());
        } else {
            // For broadcast-wide events, get all originally targeted users.
            log.debug("Handling broadcast-wide {} event.", event.getEventType());
            targetUsers = userBroadcastTargetRepository.findUserIdsByBroadcastId(event.getBroadcastId());
        }
        
        // The rest of the logic uses the correctly-scoped 'targetUsers' list
        if (!targetUsers.isEmpty()) {
            log.info("Scattering {} user-specific '{}' events to worker topics", targetUsers.size(), event.getEventType());
            List<OutboxEvent> eventsToPublish = targetUsers.stream()
                .map(userId -> {
                    Map<String, UserConnectionInfo> userConnections = cacheService.getConnectionsForUser(userId);

                    if (!userConnections.isEmpty()) {
                        UserConnectionInfo connectionInfo = userConnections.values().iterator().next();

                        String topicName = connectionInfo.getClusterName() + "-" + connectionInfo.getPodId();
                        log.info("orchestrateBroadcastEvents TopicName: {}",topicName);
                        
                        return createWorkerOutboxEvent(event, userId, topicName);
                    } else {
                        log.debug("User {} is offline. Caching pending event for broadcast {}.", userId, event.getBroadcastId());
                        cacheService.cachePendingEvent(event.toBuilder().userId(userId).build());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            if (!eventsToPublish.isEmpty()) {
                outboxEventPublisher.publishBatch(eventsToPublish);
            }
        }
        
        acknowledgment.acknowledge();
    }

    private OutboxEvent createWorkerOutboxEvent(MessageDeliveryEvent originalEvent, String userId, String topicName) {
        MessageDeliveryEvent userSpecificEvent = originalEvent.toBuilder()
                .eventId(UUID.randomUUID().toString())
                .userId(userId)
                .build();
        try {
            String payloadJson = objectMapper.writeValueAsString(userSpecificEvent);
            return OutboxEvent.builder()
                    .id(UUID.fromString(userSpecificEvent.getEventId()))
                    .aggregateType(userSpecificEvent.getClass().getSimpleName())
                    .aggregateId(userSpecificEvent.getUserId())
                    .eventType(userSpecificEvent.getEventType())
                    .topic(topicName)
                    .payload(payloadJson)
                    .createdAt(userSpecificEvent.getTimestamp())
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Could not serialize payload for user {}. Event will be skipped.", userId, e);
            return null;
        }
    }
}