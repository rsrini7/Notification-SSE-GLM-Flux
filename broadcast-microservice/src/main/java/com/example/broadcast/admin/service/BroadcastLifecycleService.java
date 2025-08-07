package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.admin.dto.BroadcastRequest;
import com.example.broadcast.admin.dto.BroadcastResponse;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.exception.UserServiceUnavailableException;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.BroadcastStatistics;
import com.example.broadcast.shared.model.OutboxEvent; // Import OutboxEvent
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages the full lifecycle of a broadcast, from creation and scheduling
 * to cancellation and expiration. This service handles all state-changing
 * (command) operations for broadcasts.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastLifecycleService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final BroadcastTargetingService broadcastTargetingService;
    private final TestingConfigurationService testingConfigurationService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final BroadcastMapper broadcastMapper;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new broadcast.
     * If scheduled, it's saved with a SCHEDULED status; otherwise, it's processed immediately.
     */
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public BroadcastResponse createBroadcast(BroadcastRequest request) {
        log.info("Creating broadcast from sender: {}, target: {}", request.getSenderId(), request.getTargetType());
        BroadcastMessage broadcast = buildBroadcastFromRequest(request);

        if (broadcast.getExpiresAt() != null && broadcast.getExpiresAt().isBefore(ZonedDateTime.now(ZoneOffset.UTC))) {
            log.warn("Broadcast creation request for an already expired message. Expiration: {}", broadcast.getExpiresAt());
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcast = broadcastRepository.save(broadcast);
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(ZonedDateTime.now(ZoneOffset.UTC))) {
            broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast with ID: {} is scheduled for: {}", broadcast.getId(), broadcast.getScheduledAt());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast = broadcastRepository.save(broadcast);
        return triggerBroadcast(broadcast);
    }

    /**
     * Processes a previously scheduled broadcast, making it active and triggering delivery.
     */
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public void processScheduledBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
        broadcastRepository.update(broadcast);
        triggerBroadcast(broadcast);
    }

    /**
     * Cancels a broadcast, updating its status and notifying users via an event.
     */
    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name());
        broadcastRepository.update(broadcast);

        int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} pending user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);
        publishLifecycleEvent(broadcast, Constants.EventType.CANCELLED, "Broadcast CANCELLED");
        log.info("Broadcast cancelled: {}. Published cancellation events to outbox.", id);
    }

    /**
     * Expires a broadcast, updating its status and notifying users.
     */
    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        if (Constants.BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) {
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcastRepository.update(broadcast);
            int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(broadcastId, Constants.DeliveryStatus.SUPERSEDED.name());
            log.info("Updated {} pending user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId);
            publishLifecycleEvent(broadcast, Constants.EventType.EXPIRED, "Broadcast EXPIRED");
            log.info("Broadcast expired: {}. Published expiration events to outbox.", broadcastId);
        }
    }

    private BroadcastResponse triggerBroadcast(BroadcastMessage broadcast) {
        boolean shouldFail = testingConfigurationService.isKafkaConsumerFailureEnabled();
        if (shouldFail) {
            log.info("Kafka failure mode is enabled. This broadcast's events will be marked for failure.");
            testingConfigurationService.setKafkaConsumerFailureEnabled(false);
        }

        List<UserBroadcastMessage> userBroadcasts = broadcastTargetingService.createUserBroadcastMessagesForBroadcast(broadcast);
        int totalTargeted = userBroadcasts.size();
        if (totalTargeted > 0) {
            log.info("Broadcast {} targeting {} users.", broadcast.getId(), totalTargeted);
            initializeStatistics(broadcast.getId(), totalTargeted);
            userBroadcastRepository.batchInsert(userBroadcasts);

            String topicName = getTopicName(broadcast.getTargetType());
            List<OutboxEvent> eventsToPublish = new ArrayList<>();
            for (UserBroadcastMessage userMessage : userBroadcasts) {
                MessageDeliveryEvent eventPayload = createDeliveryEvent(broadcast, userMessage.getUserId(), shouldFail);
                try {
                    String payloadJson = objectMapper.writeValueAsString(eventPayload);
                    eventsToPublish.add(OutboxEvent.builder()
                        .id(UUID.fromString(eventPayload.getEventId()))
                        .aggregateType(eventPayload.getClass().getSimpleName())
                        .aggregateId(eventPayload.getUserId())
                        .eventType(eventPayload.getEventType())
                        .topic(topicName)
                        .payload(payloadJson)
                        .createdAt(eventPayload.getTimestamp())
                        .build());
                } catch (JsonProcessingException e) {
                    log.error("Critical: Failed to serialize event payload for outbox. Event for user {} will not be published.", userMessage.getUserId(), e);
                    // Continue to process other users
                }
            }
            outboxEventPublisher.publishBatch(eventsToPublish);
        } else {
            log.warn("Broadcast {} created, but no users were targeted after filtering.", broadcast.getId());
        }

        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted);
    }

    private void publishLifecycleEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        String topicName = getTopicName(broadcast.getTargetType());
        List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(broadcast.getId());
        
        List<OutboxEvent> eventsToPublish = new ArrayList<>();
        for (UserBroadcastMessage userMessage : userBroadcasts) {
            MessageDeliveryEvent eventPayload = createLifecycleEvent(broadcast, userMessage.getUserId(), eventType, message);
            try {
                String payloadJson = objectMapper.writeValueAsString(eventPayload);
                eventsToPublish.add(OutboxEvent.builder()
                    .id(UUID.fromString(eventPayload.getEventId()))
                    .aggregateType(eventPayload.getClass().getSimpleName())
                    .aggregateId(userMessage.getUserId())
                    .eventType(eventPayload.getEventType())
                    .topic(topicName)
                    .payload(payloadJson)
                    .createdAt(eventPayload.getTimestamp())
                    .build());
            } catch (JsonProcessingException e) {
                log.error("Critical: Failed to serialize lifecycle event payload for outbox. Event for user {} will not be published.", userMessage.getUserId(), e);
            }
        }
        outboxEventPublisher.publishBatch(eventsToPublish);
    }

    private MessageDeliveryEvent createDeliveryEvent(BroadcastMessage broadcast, String userId, boolean transientFailure) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .userId(userId)
                .eventType(Constants.EventType.CREATED.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(broadcast.getContent())
                .transientFailure(transientFailure)
                .build();
    }

    private MessageDeliveryEvent createLifecycleEvent(BroadcastMessage broadcast, String userId, Constants.EventType eventType, String message) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .userId(userId)
                .eventType(eventType.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(message)
                .build();
    }

    private void initializeStatistics(Long broadcastId, int totalTargeted) {
        BroadcastStatistics initialStats = BroadcastStatistics.builder()
                .broadcastId(broadcastId)
                .totalTargeted(totalTargeted)
                .totalDelivered(0)
                .totalRead(0)
                .totalFailed(0)
                .calculatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build();
        broadcastStatisticsRepository.save(initialStats);
    }

    private BroadcastMessage buildBroadcastFromRequest(BroadcastRequest request) {
        return BroadcastMessage.builder()
                .senderId(request.getSenderId())
                .senderName(request.getSenderName())
                .content(request.getContent())
                .targetType(request.getTargetType())
                .targetIds(request.getTargetIds())
                .priority(request.getPriority())
                .category(request.getCategory())
                .scheduledAt(request.getScheduledAt())
                .expiresAt(request.getExpiresAt())
                .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                .updatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .isFireAndForget(request.isFireAndForget())
                .build();
    }

    private String getTopicName(String targetType) {
        return Constants.TargetType.ALL.name().equals(targetType)
                ? appProperties.getKafka().getTopic().getNameAll()
                : appProperties.getKafka().getTopic().getNameSelected();
    }
}