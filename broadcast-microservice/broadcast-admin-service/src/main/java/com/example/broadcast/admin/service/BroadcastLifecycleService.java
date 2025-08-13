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
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastLifecycleService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
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

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(ZonedDateTime.now(ZoneOffset.UTC))) {
            broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast with ID: {} is scheduled for: {}", broadcast.getId(), broadcast.getScheduledAt());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast = broadcastRepository.save(broadcast);

        if (isFailureTest) {
            testingConfigurationService.markBroadcastForFailure(broadcast.getId());
            log.warn("Broadcast ID {} has been marked for DLT failure simulation.", broadcast.getId());
        }

        return triggerBroadcast(broadcast);
    }

    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public void processScheduledBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
        broadcastRepository.update(broadcast);
        triggerBroadcast(broadcast);
    }

    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name());
        broadcastRepository.update(broadcast);

        int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} pending user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);
        publishLifecycleEvent(broadcast, Constants.EventType.CANCELLED, "Broadcast CANCELLED");
        
        cacheService.evictActiveGroupBroadcastsCache();
        
        // CHANGED: Evict the specific broadcast from the content cache.
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
            
            
            // CHANGED: Use the new repository method to update both PENDING and DELIVERED messages to SUPERSEDED.
            // This makes the state consistent for all users, including those who already received a "Fire and Forget" message.
            int updatedCount = userBroadcastRepository.updateNonFinalStatusesByBroadcastId(broadcastId, Constants.DeliveryStatus.SUPERSEDED.name());
            log.info("Updated {} PENDING or DELIVERED user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId);
            
            publishLifecycleEvent(broadcast, Constants.EventType.EXPIRED, "Broadcast EXPIRED");
            
            cacheService.evictActiveGroupBroadcastsCache();

            // CHANGED: Evict the specific broadcast from the content cache.
            cacheService.evictBroadcastContent(broadcastId);

            log.info("Broadcast expired: {}. Published expiration events to outbox and evicted caches.", broadcastId);
        } else {
            log.info("Broadcast {} was already in a non-active state ({}). No expiration action needed.", broadcastId, broadcast.getStatus());
        }
    }

    private BroadcastResponse triggerBroadcast(BroadcastMessage broadcast) {

        String targetType = broadcast.getTargetType();
        int totalTargeted = 0;

        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            totalTargeted = fanOutOnWrite(broadcast);
        } else if (Constants.TargetType.ROLE.name().equals(targetType) || Constants.TargetType.ALL.name().equals(targetType)) {
            totalTargeted = fanOutOnRead(broadcast);
            cacheService.evictActiveGroupBroadcastsCache();
        }

        if (totalTargeted > 0) {
            initializeStatistics(broadcast.getId(), totalTargeted);
        } else {
            log.warn("Broadcast {} created, but no users were targeted.", broadcast.getId());
        }

        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted);
    }

    private int fanOutOnWrite(BroadcastMessage broadcast) { 
        List<UserBroadcastMessage> userBroadcasts = broadcastTargetingService.createUserBroadcastMessagesForBroadcast(broadcast);
        int totalTargeted = userBroadcasts.size();
        if (totalTargeted > 0) {
            log.info("Fan-out on write for broadcast {} targeting {} users.", broadcast.getId(), totalTargeted);
            userBroadcastRepository.batchInsert(userBroadcasts);

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
        return totalTargeted;
    }

    private int fanOutOnRead(BroadcastMessage broadcast) {

        log.info("Fan-out on read for broadcast {} targeting {}", broadcast.getId(), broadcast.getTargetType());
        
        // This assumes broadcastTargetingService has a method to just get the count efficiently.
        int totalTargeted = broadcastTargetingService.countTargetUsers(broadcast);

        if (totalTargeted > 0) {
            String topicName = appProperties.getKafka().getTopic().getNameGroup();

            MessageDeliveryEvent eventPayload = createGroupDeliveryEvent(broadcast);
            try {
                String payloadJson = objectMapper.writeValueAsString(eventPayload);
                outboxEventPublisher.publish(createOutboxEvent(eventPayload, topicName, payloadJson));
            } catch (JsonProcessingException e) {
                log.error("Critical: Failed to serialize group event payload for outbox. Broadcast {} will not be published.", broadcast.getId(), e);
            }
        }
        return totalTargeted;
    }

    private void publishLifecycleEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        String topicName = getTopicName(broadcast.getTargetType());
        List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(broadcast.getId());

        if (!userBroadcasts.isEmpty()) {
            // This is the existing logic for SELECTED users, which is correct.
            List<OutboxEvent> eventsToPublish = new ArrayList<>();
            for (UserBroadcastMessage userMessage : userBroadcasts) {
                MessageDeliveryEvent eventPayload = createLifecycleEvent(broadcast, userMessage.getUserId(), eventType, message);
                try {
                    String payloadJson = objectMapper.writeValueAsString(eventPayload);
                    eventsToPublish.add(createOutboxEvent(eventPayload, topicName, payloadJson));
                } catch (JsonProcessingException e) {
                    log.error("Critical: Failed to serialize lifecycle event payload for outbox. Event for user {} will not be published.", userMessage.getUserId(), e);
                }
            }
            outboxEventPublisher.publishBatch(eventsToPublish);
        } else if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) || Constants.TargetType.ROLE.name().equals(broadcast.getTargetType())) {
            // NEW LOGIC: If no user records exist, it's a group broadcast. Publish a single group event.
            log.info("Publishing group lifecycle event ({}) for broadcast {}", eventType.name(), broadcast.getId());
            MessageDeliveryEvent eventPayload = createGroupLifecycleEvent(broadcast, eventType, message);
            try {
                String payloadJson = objectMapper.writeValueAsString(eventPayload);
                outboxEventPublisher.publish(createOutboxEvent(eventPayload, topicName, payloadJson));
            } catch (JsonProcessingException e) {
                log.error("Critical: Failed to serialize group lifecycle event payload for outbox for broadcast {}.", broadcast.getId(), e);
            }
        }
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

    private String getTopicName(String targetType) {
        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            return appProperties.getKafka().getTopic().getNameSelected();
        }
        return appProperties.getKafka().getTopic().getNameGroup();
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