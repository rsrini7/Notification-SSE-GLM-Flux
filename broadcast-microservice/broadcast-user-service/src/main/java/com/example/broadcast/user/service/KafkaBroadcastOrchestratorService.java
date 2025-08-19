package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.service.UserService;
import java.util.Collections;
import java.util.stream.Stream;
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
    private final BroadcastRepository broadcastRepository;
    private final UserService userService;

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

        BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId()).orElse(null);

        if (broadcast == null) {
            log.error("Cannot orchestrate event. BroadcastMessage with ID {} not found. Acknowledging to avoid retry.", event.getBroadcastId());
            acknowledgment.acknowledge();
            return;
        }

        final List<String> targetUsers;
        
        String targetType = broadcast.getTargetType();

        if (Constants.EventType.READ.name().equals(event.getEventType())) {
            // READ events are always for a single user
            targetUsers = List.of(event.getUserId());
        } else if (Constants.TargetType.PRODUCT.name().equals(targetType)) {
            // PRODUCT type still uses the pre-computed list
            log.debug("Distributing for PRODUCT broadcast. Reading from broadcast_user_targets table.");
            targetUsers = userBroadcastTargetRepository.findUserIdsByBroadcastId(broadcast.getId());
        } else {
            // ALL, ROLE, and SELECTED types determine the user list dynamically NOW.
            log.debug("Distributing for fan-out-on-read broadcast (ALL, ROLE, or SELECTED).");
            targetUsers = determineFanOutOnReadUsers(broadcast);
        }
        
        if (targetUsers.isEmpty()) {
            log.warn("Orchestrator found no target users for broadcast ID {}. No events will be scattered.", broadcast.getId());
        } else {
            log.info("Scattering {} user-specific '{}' events to worker topics for broadcast ID {}", targetUsers.size(), event.getEventType(), broadcast.getId());
            List<OutboxEvent> eventsToPublish = targetUsers.stream()
                .map(userId -> {
                    Map<String, UserConnectionInfo> userConnections = cacheService.getConnectionsForUser(userId);
                    if (!userConnections.isEmpty()) {
                        UserConnectionInfo connectionInfo = userConnections.values().iterator().next();
                        String workerTopicName = connectionInfo.getClusterName() + "-" + connectionInfo.getPodId();
                        return createWorkerOutboxEvent(event, userId, workerTopicName);
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

    private List<String> determineFanOutOnReadUsers(BroadcastMessage broadcast) {
        String targetType = broadcast.getTargetType();
        if (Constants.TargetType.ALL.name().equals(targetType)) {
            return userService.getAllUserIds();
        }
        if (Constants.TargetType.ROLE.name().equals(targetType)) {
            return broadcast.getTargetIds().stream()
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
        }
        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            return broadcast.getTargetIds();
        }
        return Collections.emptyList();
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