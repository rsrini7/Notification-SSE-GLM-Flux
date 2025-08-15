package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.admin.BroadcastRequest;
import com.example.broadcast.shared.dto.admin.BroadcastResponse;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.exception.UserServiceUnavailableException;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.BroadcastStatistics;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.service.TestingConfigurationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.List;
import java.util.UUID;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastLifecycleService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final UserBroadcastTargetRepository userBroadcastTargetRepository; 
    private final BroadcastTargetingService broadcastTargetingService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final BroadcastMapper broadcastMapper;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService;
    private final TestingConfigurationService testingConfigurationService;

    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public BroadcastResponse createBroadcast(BroadcastRequest request) {
        // Atomically check and consume the "armed" state for the test mode.
        boolean isFailureTest = testingConfigurationService.consumeArmedState();

        log.info("Creating broadcast from sender: {}, target: {}", request.getSenderId(), request.getTargetType());
        BroadcastMessage broadcast = buildBroadcastFromRequest(request);

        if (broadcast.getExpiresAt() != null && broadcast.getExpiresAt().isBefore(ZonedDateTime.now(ZoneOffset.UTC))) {
            log.warn("Broadcast creation request for an already expired message. Expiration: {}", broadcast.getExpiresAt());
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcast = broadcastRepository.save(broadcast);
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        // MODIFIED: Use the configured delay to determine if a broadcast is truly "scheduled"
        // or if it needs to be treated as "immediate".
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        long fetchDelayMs = appProperties.getSimulation().getUserFetchDelayMs();
        ZonedDateTime precomputationThreshold = now.plus(fetchDelayMs, ChronoUnit.MILLIS);

        // If the scheduled time is far enough in the future, save it and let the scheduler handle it.
        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(precomputationThreshold)) {
            broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast ID: {} is a true scheduled broadcast. Saving with SCHEDULED status.", broadcast.getId());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        // If it's scheduled for the future, just save it and let the pre-computation scheduler handle it.
        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(ZonedDateTime.now(ZoneOffset.UTC))) {
            broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast with ID: {} is scheduled for: {}", broadcast.getId(), broadcast.getScheduledAt());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        // For IMMEDIATE broadcasts OR broadcasts scheduled too close to now:
        // 1. Save the broadcast with PREPARING status to get an ID.
        log.info("Broadcast ID: {} is immediate or scheduled too close. Treating as immediate. Saving with PREPARING status.", broadcast.getId());
        broadcast.setStatus(Constants.BroadcastStatus.PREPARING.name());
        broadcast = broadcastRepository.save(broadcast);

        if (isFailureTest) {
            testingConfigurationService.markBroadcastForFailure(broadcast.getId());
            log.warn("Broadcast ID {} has been marked for DLT failure simulation.", broadcast.getId());
        }

        // 2. Trigger the ASYNCHRONOUS pre-computation task.
        log.info("Triggering async user pre-computation for immediate broadcast ID: {}", broadcast.getId());
        broadcastTargetingService.precomputeAndStoreTargetUsers(broadcast.getId());

        // 3. Immediately return a 202 Accepted-style response to the user.
        return broadcastMapper.toBroadcastResponse(broadcast, 0);
    }

    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public void processScheduledBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
        broadcastRepository.update(broadcast);
        
        List<String> targetUserIds = userBroadcastTargetRepository.findUserIdsByBroadcastId(broadcastId);
        triggerCreateBroadcastEventFromPrefetchedUsers(broadcast, targetUserIds);
    }

    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name());
        broadcastRepository.update(broadcast);

        // This database update for pending users is correct.
        int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} pending user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);

        // Trigger event logic is now handled correctly based on target type.
        triggerCancelOrExpireBroadcastEvent(broadcast, Constants.EventType.CANCELLED, "Broadcast CANCELLED");
        
        // Clean up the pre-computed user list
        int deletedCount = userBroadcastTargetRepository.deleteByBroadcastId(id);
        log.info("Cleaned up {} pre-computed user targets for cancelled broadcast ID: {}", deletedCount, id);

        cacheService.evictActiveGroupBroadcastsCache();
        cacheService.evictBroadcastContent(id);
        log.info("Broadcast cancelled: {}. Published cancellation events to outbox and evicted caches.", id);
    }

    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        if (Constants.BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) {
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcastRepository.update(broadcast);
            
            int updatedCount = userBroadcastRepository.updateNonFinalStatusesByBroadcastId(broadcastId, Constants.DeliveryStatus.SUPERSEDED.name());
            log.info("Updated {} PENDING or DELIVERED user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId);
            
            // Trigger event logic is now handled correctly based on target type.
            triggerCancelOrExpireBroadcastEvent(broadcast, Constants.EventType.EXPIRED, "Broadcast EXPIRED");
            
            // Clean up the pre-computed user list
            int deletedCount = userBroadcastTargetRepository.deleteByBroadcastId(broadcastId);
            log.info("Cleaned up {} pre-computed user targets for expired broadcast ID: {}", deletedCount, broadcastId);

            cacheService.evictActiveGroupBroadcastsCache();
            cacheService.evictBroadcastContent(broadcastId);

            log.info("Broadcast expired: {}. Published expiration events to outbox and evicted caches.", broadcastId);
        } else {
            log.info("Broadcast {} was already in a non-active state ({}). No expiration action needed.", broadcastId, broadcast.getStatus());
        }
    }

    private void triggerCancelOrExpireBroadcastEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        // Case 1: For SELECTED users, we must fan-out user-specific events.
        if (Constants.TargetType.SELECTED.name().equals(broadcast.getTargetType())) {
            List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(broadcast.getId());
            if (!userBroadcasts.isEmpty()) {
                String topicName = appProperties.getKafka().getTopic().getNameUserActions(); // Topic for workers
                log.info("Publishing {} user-specific lifecycle events ({}) for SELECTED broadcast {} to topic {}", userBroadcasts.size(), eventType.name(), broadcast.getId(), topicName);

                List<OutboxEvent> eventsToPublish = userBroadcasts.stream().map(userMessage -> {
                    MessageDeliveryEvent eventPayload = createLifecycleEvent(broadcast, userMessage.getUserId(), eventType, message);
                    try {
                        String payloadJson = objectMapper.writeValueAsString(eventPayload);
                        return createOutboxEvent(eventPayload, topicName, payloadJson);
                    } catch (JsonProcessingException e) {
                        log.error("Failed to serialize action event for user {}: {}", userMessage.getUserId(), e.getMessage());
                        return null;
                    }
                }).filter(Objects::nonNull).collect(Collectors.toList());
                outboxEventPublisher.publishBatch(eventsToPublish);
            }
        // Case 2: For GROUP users, publish a single orchestration event.
        } else {
            String topicName = appProperties.getKafka().getTopic().getNameActionsOrchestration(); // Topic for the leader
            log.info("Publishing single group lifecycle event ({}) for GROUP broadcast {} to topic {}", eventType.name(), broadcast.getId(), topicName);
            
            MessageDeliveryEvent eventPayload = createGroupLifecycleEvent(broadcast, eventType, message);
            try {
                String payloadJson = objectMapper.writeValueAsString(eventPayload);
                outboxEventPublisher.publish(createOutboxEvent(eventPayload, topicName, payloadJson));
            } catch (JsonProcessingException e) {
                log.error("Critical: Failed to serialize group lifecycle event for outbox for broadcast {}.", broadcast.getId(), e);
            }
        }
    }

    private BroadcastResponse triggerCreateBroadcastEventFromPrefetchedUsers(BroadcastMessage broadcast, List<String> targetUserIds) {
         int totalTargeted = targetUserIds.size();
        if (totalTargeted == 0) {
            log.warn("Broadcast {} created, but no users were targeted.", broadcast.getId());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        // Initialize statistics for all types
        initializeStatistics(broadcast.getId(), totalTargeted);

        String targetType = broadcast.getTargetType();

        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            // --- STRATEGY 1: FAN-OUT ON WRITE (for SELECTED users) ---
            // This is for high-touch messages where we need to track individual status.
            log.info("Using fan-out-on-write strategy for SELECTED broadcast ID: {}", broadcast.getId());
            fanOutOnWriteFromPrefetchedUsers(broadcast, targetUserIds);
        } else {
            // --- STRATEGY 2: KAFKA-BASED FAN-OUT ON READ (for ALL/ROLE users) ---
            // This avoids writing 400k records to the user_broadcast_messages table.
            log.info("Using Kafka-based fan-out-on-read for {} broadcast ID: {}", targetType, broadcast.getId());
            publishSingleOrchestrationEvent(broadcast);
        }

        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted);
    }

    /**
     * NEW HELPER: Publishes a single event to the orchestration topic.
     * The leader consumer will read the user list from the broadcast_user_targets table.
     */
    private void publishSingleOrchestrationEvent(BroadcastMessage broadcast) {
        String topicName = appProperties.getKafka().getTopic().getNameGroupOrchestration();
        MessageDeliveryEvent eventPayload = createGroupDeliveryEvent(broadcast);
        try {
            String payloadJson = objectMapper.writeValueAsString(eventPayload);
            outboxEventPublisher.publish(createOutboxEvent(eventPayload, topicName, payloadJson));
            log.info("Published single orchestration event for broadcast {} to topic {}", broadcast.getId(), topicName);
        } catch (JsonProcessingException e) {
            log.error("Critical: Failed to serialize group event payload for outbox. Broadcast {} will not be published.", broadcast.getId(), e);
        }
    }

    private void fanOutOnWriteFromPrefetchedUsers(BroadcastMessage broadcast, List<String> targetUserIds) {
        log.info("Fan-out on write for broadcast {} targeting {} pre-fetched users.", broadcast.getId(), targetUserIds.size());

        // 1. Create the UserBroadcastMessage objects from the user ID strings
        List<UserBroadcastMessage> userBroadcasts = targetUserIds.stream()
            .map(userId -> UserBroadcastMessage.builder()
                .broadcastId(broadcast.getId())
                .userId(userId)
                .deliveryStatus(Constants.DeliveryStatus.PENDING.name())
                .readStatus(Constants.ReadStatus.UNREAD.name())
                .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                .updatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build())
            .collect(Collectors.toList());

        // 2. Batch insert the user-specific records
        userBroadcastRepository.batchInsert(userBroadcasts);

        // 3. Create and publish outbox events
        String topicName = appProperties.getKafka().getTopic().getNameSelected();
        List<OutboxEvent> eventsToPublish = new ArrayList<>();
        for (UserBroadcastMessage userMessage : userBroadcasts) {
            MessageDeliveryEvent eventPayload = createDeliveryEvent(broadcast, userMessage.getUserId());
            try {
                String payloadJson = objectMapper.writeValueAsString(eventPayload);
                eventsToPublish.add(createOutboxEvent(eventPayload, topicName, payloadJson));
            } catch (JsonProcessingException e) {
                log.error("Critical: Failed to serialize event payload for outbox. Event for user {} will not be published.", userMessage.getUserId(), e);
            }
        }
        outboxEventPublisher.publishBatch(eventsToPublish);
    }

    private MessageDeliveryEvent createGroupLifecycleEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .eventType(eventType.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(message)
                .build();
    }
    
    private OutboxEvent createOutboxEvent(MessageDeliveryEvent eventPayload, String topicName, String payloadJson) {
        return OutboxEvent.builder()
            .id(UUID.fromString(eventPayload.getEventId()))
            .aggregateType(eventPayload.getClass().getSimpleName())
            .aggregateId(eventPayload.getUserId() != null ? eventPayload.getUserId() : eventPayload.getBroadcastId().toString())
            .eventType(eventPayload.getEventType())
            .topic(topicName)
            .payload(payloadJson)
            .createdAt(eventPayload.getTimestamp())
            .build();
    }

    private MessageDeliveryEvent createDeliveryEvent(BroadcastMessage broadcast, String userId) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .userId(userId)
                .eventType(Constants.EventType.CREATED.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(broadcast.getContent())
                .isFireAndForget(broadcast.isFireAndForget())
                .build();
    }
    
    private MessageDeliveryEvent createGroupDeliveryEvent(BroadcastMessage broadcast) {

        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .eventType(Constants.EventType.CREATED.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(broadcast.getContent())
                .isFireAndForget(broadcast.isFireAndForget())
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

    /**
     * Marks a broadcast as FAILED in a new, independent transaction.
     * This is critical for ensuring the state is updated even if the calling
     * DLT process has issues.
     * @param broadcastId The ID of the broadcast to fail.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failBroadcast(Long broadcastId) {
        if (broadcastId == null) return;
        
        broadcastRepository.updateStatus(broadcastId, Constants.BroadcastStatus.FAILED.name());
        cacheService.evictActiveGroupBroadcastsCache();
        log.warn("Marked entire BroadcastMessage {} as FAILED in a new transaction and evicted cache.", broadcastId);
    }
}