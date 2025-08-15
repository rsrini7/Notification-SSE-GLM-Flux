package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Collections;

// "Scatter-Gather" pattern introduced to avoid the "thundering herd" problem.
//  This class consumed by single "leader" pod using a static group ID.
//  This leader publish user-specific tasks to a new user ( "worker" consumer kept in KafkaConsumerService.java)
@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaBroadcastOrchestratorService {

    private final BroadcastRepository broadcastRepository;
    private final UserService userService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    @KafkaListener(
        topics = "${broadcast.kafka.topic.name-group-orchestration}",
        groupId = "${broadcast.kafka.consumer.group-orchestration-group-id}", // Static Group ID
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void orchestrateGroupBroadcast(MessageDeliveryEvent event, Acknowledgment acknowledgment) {
        log.info("Orchestration event received for broadcast ID: {}. Fetching user list...", event.getBroadcastId());
        
        BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId()).orElse(null);
        if (broadcast == null) {
            log.warn("Cannot orchestrate broadcast {}: not found.", event.getBroadcastId());
            acknowledgment.acknowledge();
            return;
        }

        // 1. Fetch the full list of targeted user IDs ONCE.
        List<String> allTargetedUsers = determineAllTargetedUsers(broadcast);

        if (!allTargetedUsers.isEmpty()) {
            // 2. "Scatter" the work by publishing user-specific events to the 'UserGroup' topic.
            String topicName = appProperties.getKafka().getTopic().getNameUserGroup();
            log.info("Scattering {} user-specific delivery events to topic '{}'", allTargetedUsers.size(), topicName);
            
            List<OutboxEvent> eventsToPublish = allTargetedUsers.stream()
                .map(userId -> createDeliveryOutboxEvent(broadcast, userId, topicName))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            outboxEventPublisher.publishBatch(eventsToPublish);
        }
        
        acknowledgment.acknowledge();
    }

    /**
     * NEW: This is the "leader" consumer for actions (CANCELLED, EXPIRED, READ).
     * It uses a STATIC group ID to ensure it runs only once per event.
     */
    @KafkaListener(
        topics = "${broadcast.kafka.topic.name-actions-orchestration}",
        groupId = "${broadcast.kafka.consumer.actions-orchestration-group-id}", // Static Group ID
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void orchestrateActionEvent(MessageDeliveryEvent event, Acknowledgment acknowledgment) {
        log.info("Orchestration event received for action '{}' on broadcast ID: {}. Fetching user list...", event.getEventType(), event.getBroadcastId());

        // A READ event is already user-specific, so we can forward it directly.
        if (event.getEventType().equals(Constants.EventType.READ.name())) {
            log.info("Forwarding user-specific READ event for user {} directly to worker topic.", event.getUserId());
            OutboxEvent userAction = createActionOutboxEvent(event, event.getUserId());
            if (userAction != null) {
                outboxEventPublisher.publish(userAction);
            }
            acknowledgment.acknowledge();
            return;
        }

        // For CANCELLED/EXPIRED, we fan-out.
        BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId()).orElse(null);
        if (broadcast == null) {
            log.warn("Cannot orchestrate action for broadcast {}: not found.", event.getBroadcastId());
            acknowledgment.acknowledge();
            return;
        }

        List<String> allTargetedUsers = determineAllTargetedUsers(broadcast);

        if (!allTargetedUsers.isEmpty()) {
            log.info("Scattering {} user-specific action events to topic '{}'", allTargetedUsers.size(), appProperties.getKafka().getTopic().getNameUserActions());
            List<OutboxEvent> eventsToPublish = allTargetedUsers.stream()
                .map(userId -> createActionOutboxEvent(event, userId))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            outboxEventPublisher.publishBatch(eventsToPublish);
        }
        
        acknowledgment.acknowledge();
    }

    private OutboxEvent createActionOutboxEvent(MessageDeliveryEvent originalEvent, String userId) {
        MessageDeliveryEvent userEvent = originalEvent.toBuilder().userId(userId).build();
        try {
            String payloadJson = objectMapper.writeValueAsString(userEvent);
            return OutboxEvent.builder()
                    .id(UUID.randomUUID()) // Generate a new ID for the sub-task event
                    .aggregateType(userEvent.getClass().getSimpleName())
                    .aggregateId(userId)
                    .eventType(userEvent.getEventType())
                    .topic(appProperties.getKafka().getTopic().getNameUserActions()) // Publish to the user-actions topic
                    .payload(payloadJson)
                    .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Could not serialize payload for user action. User: {}, Event: {}", userId, originalEvent.getEventType(), e);
            return null;
        }
    }

    private List<String> determineAllTargetedUsers(BroadcastMessage broadcast) {
        if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
            return userService.getAllUserIds();
        } else if (Constants.TargetType.ROLE.name().equals(broadcast.getTargetType())) {
            return broadcast.getTargetIds().stream()
                .flatMap(role -> userService.getUserIdsByRole(role).stream())
                .distinct()
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private OutboxEvent createDeliveryOutboxEvent(BroadcastMessage broadcast, String userId, String topicName) {
        MessageDeliveryEvent eventPayload = MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .userId(userId)
                .eventType(Constants.EventType.CREATED.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(broadcast.getContent())
                .isFireAndForget(broadcast.isFireAndForget())
                .build();
        try {
            String payloadJson = objectMapper.writeValueAsString(eventPayload);
            return OutboxEvent.builder()
                    .id(UUID.fromString(eventPayload.getEventId()))
                    .aggregateType(eventPayload.getClass().getSimpleName())
                    .aggregateId(eventPayload.getUserId())
                    .eventType(eventPayload.getEventType())
                    .topic(topicName)
                    .payload(payloadJson)
                    .createdAt(eventPayload.getTimestamp())
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Could not serialize payload for user {}. Event will be skipped.", userId, e);
            return null;
        }
    }
}