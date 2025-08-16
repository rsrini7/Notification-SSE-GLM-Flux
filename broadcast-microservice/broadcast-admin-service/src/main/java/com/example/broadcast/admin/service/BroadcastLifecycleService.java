// file: broadcast-microservice/broadcast-admin-service/src/main/java/com/example/broadcast/admin/service/BroadcastLifecycleService.java
package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.admin.BroadcastRequest;
import com.example.broadcast.shared.dto.admin.BroadcastResponse;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.exception.UserServiceUnavailableException;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.BroadcastStatistics;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.*;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.service.TestingConfigurationService;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    /**
     * Main entry point for creating a broadcast. It classifies the broadcast as either
     * truly scheduled or effectively immediate based on the configured fetch delay.
     */
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

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        long fetchDelayMs = appProperties.getSimulation().getUserFetchDelayMs();
        ZonedDateTime precomputationThreshold = now.plus(fetchDelayMs, ChronoUnit.MILLIS);

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(precomputationThreshold)) {
            broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast ID: {} is a true scheduled broadcast. Saving with SCHEDULED status.", broadcast.getId());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        // For IMMEDIATE broadcasts OR broadcasts scheduled too close to now:
        // 1. Save the broadcast with PREPARING status to get an ID.
        log.info("Broadcast is immediate or scheduled too close. Treating as immediate. Saving with PREPARING status.");
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

    /**
     * Called by the Activation Scheduler to make a READY broadcast ACTIVE.
     */
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public void processReadyBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
        broadcastRepository.update(broadcast);
        
        List<String> targetUserIds = userBroadcastTargetRepository.findUserIdsByBroadcastId(broadcastId);
        triggerCreateBroadcastEventFromPrefetchedUsers(broadcast, targetUserIds);
    }

    /**
     * Called when a broadcast is cancelled.
     */
    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name());
        broadcastRepository.update(broadcast);

        int updatedCount = userBroadcastRepository.updateNonFinalStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} PENDING or DELIVERED user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);

        triggerCancelOrExpireBroadcastEvent(broadcast, Constants.EventType.CANCELLED, "Broadcast CANCELLED");
        
        cacheService.evictActiveGroupBroadcastsCache();
        cacheService.evictBroadcastContent(id);
        log.info("Broadcast cancelled: {}. Published cancellation events to outbox and evicted caches.", id);
    }

    /**
     * Called by the Expiration Scheduler.
     */
    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));

        if (Constants.BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) {
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcastRepository.update(broadcast);
            
            int updatedCount = userBroadcastRepository.updateNonFinalStatusesByBroadcastId(broadcastId, Constants.DeliveryStatus.SUPERSEDED.name());
            log.info("Updated {} PENDING or DELIVERED user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId);
            
            triggerCancelOrExpireBroadcastEvent(broadcast, Constants.EventType.EXPIRED, "Broadcast EXPIRED");
            
            cacheService.evictActiveGroupBroadcastsCache();
            cacheService.evictBroadcastContent(broadcastId);
            log.info("Broadcast expired: {}. Published expiration events to outbox and evicted caches.", broadcastId);
        } else {
            log.info("Broadcast {} was already in a non-active state ({}). No expiration action needed.", broadcastId, broadcast.getStatus());
        }
    }

    private BroadcastResponse triggerCreateBroadcastEventFromPrefetchedUsers(BroadcastMessage broadcast, List<String> targetUserIds) {
        int totalTargeted = targetUserIds.size();
        if (totalTargeted == 0) {
            log.warn("Broadcast {} created, but no users were targeted.", broadcast.getId());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        initializeStatistics(broadcast.getId(), totalTargeted);

        if (Constants.TargetType.SELECTED.name().equals(broadcast.getTargetType())) {
            publishEventsToWorkerTopics(broadcast, targetUserIds, Constants.EventType.CREATED, broadcast.getContent());
        } else {
            publishSingleOrchestrationEvent(broadcast, Constants.EventType.CREATED, broadcast.getContent());
        }

        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted);
    }

    private void triggerCancelOrExpireBroadcastEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        String targetType = broadcast.getTargetType();
        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            List<String> targetUserIds = userBroadcastTargetRepository.findUserIdsByBroadcastId(broadcast.getId());
            publishEventsToWorkerTopics(broadcast, targetUserIds, eventType, message);
        } else {
            publishSingleOrchestrationEvent(broadcast, eventType, message);
        }
    }

    private void publishEventsToWorkerTopics(BroadcastMessage broadcast, List<String> userIds, Constants.EventType eventType, String message) {
        List<OutboxEvent> eventsToPublish = new ArrayList<>();
        String topicPrefix = appProperties.getKafka().getTopic().getNameWorkerPrefix();
        String clusterName = appProperties.getClusterName();

        if (eventType == Constants.EventType.CREATED) {
            List<UserBroadcastMessage> userBroadcasts = userIds.stream()
                .map(userId -> UserBroadcastMessage.builder()
                    .broadcastId(broadcast.getId())
                    .userId(userId)
                    .deliveryStatus(Constants.DeliveryStatus.PENDING.name())
                    .readStatus(Constants.ReadStatus.UNREAD.name())
                    .build())
                .collect(Collectors.toList());
            userBroadcastRepository.batchInsert(userBroadcasts);
        }

        for (String userId : userIds) {
            MessageDeliveryEvent eventPayload = createLifecycleEvent(broadcast, userId, eventType, message);
            UserConnectionInfo connectionInfo = cacheService.getUserConnectionInfo(userId);
            log.debug("User Connection Info : {}", connectionInfo);
            if (connectionInfo != null && connectionInfo.getPodId() != null) {
                String topicName = clusterName + "-" + topicPrefix + connectionInfo.getPodId();
                eventsToPublish.add(createOutboxEvent(eventPayload, topicName, userId));
            } else {
                cacheService.cachePendingEvent(eventPayload);
            }
        }
        if (!eventsToPublish.isEmpty()) {
            outboxEventPublisher.publishBatch(eventsToPublish);
        }
    }
    
    private void publishSingleOrchestrationEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        String topicName = appProperties.getKafka().getTopic().getNameOrchestration();
        MessageDeliveryEvent eventPayload = createOrchestrationEvent(broadcast, eventType, message);
        outboxEventPublisher.publish(createOutboxEvent(eventPayload, topicName, broadcast.getId().toString()));
    }

    private void initializeStatistics(Long broadcastId, int totalTargeted) {
        BroadcastStatistics stats = BroadcastStatistics.builder()
                .broadcastId(broadcastId)
                .totalTargeted(totalTargeted)
                .totalDelivered(0)
                .totalRead(0)
                .totalFailed(0)
                .calculatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build();
        broadcastStatisticsRepository.save(stats);
    }

private MessageDeliveryEvent createOrchestrationEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .eventType(eventType.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(message) // Use the message parameter
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
                .message(message) // Use the message parameter
                .isFireAndForget(broadcast.isFireAndForget())
                .build();
    }

    private OutboxEvent createOutboxEvent(MessageDeliveryEvent eventPayload, String topicName, String aggregateId) {
        try {
            String payloadJson = objectMapper.writeValueAsString(eventPayload);
            return OutboxEvent.builder()
                .id(UUID.fromString(eventPayload.getEventId()))
                .aggregateType(eventPayload.getClass().getSimpleName())
                .aggregateId(aggregateId)
                .eventType(eventPayload.getEventType())
                .topic(topicName)
                .payload(payloadJson)
                .createdAt(eventPayload.getTimestamp())
                .build();
        } catch (JsonProcessingException e) {
            log.error("Critical: Failed to serialize event payload for outbox for aggregateId {}.", aggregateId, e);
            // In a real system, this might throw a custom exception to halt the transaction.
            return null; 
        }
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