package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.admin.BroadcastRequest;
import com.example.broadcast.shared.dto.admin.BroadcastResponse;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.exception.UserServiceUnavailableException;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.BroadcastStatistics;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.*;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.JsonUtils;
import com.example.broadcast.admin.event.BroadcastCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastLifecycleService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final UserService userService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final BroadcastMapper broadcastMapper;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public BroadcastResponse createBroadcast(BroadcastRequest request) {
        log.info("Creating broadcast from sender: {}, target: {}", request.getSenderId(), request.getTargetType());
        BroadcastMessage broadcast = broadcastMapper.toBroadcastMessage(request);

        // 1. Handle edge case for already expired messages
        if (broadcast.getExpiresAt() != null && broadcast.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            log.warn("Broadcast creation request for an already expired message. Expiration: {}", broadcast.getExpiresAt());
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcast = broadcastRepository.save(broadcast);
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }

        // 2. Delegate to the appropriate handler based on scheduling
        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC))) {
            return handleScheduledBroadcast(broadcast);
        } else {
            return handleImmediateBroadcast(broadcast);
        }
    }

    /**
     * Handles all immediate broadcast requests by delegating to the correct fan-out strategy.
     */
    private BroadcastResponse handleImmediateBroadcast(BroadcastMessage broadcast) {
        Constants.TargetType targetType = Constants.TargetType.valueOf(broadcast.getTargetType());
        switch (targetType) {
            case PRODUCT:
                return handleProductBroadcast(broadcast);
            case ALL:
                return handleAllBroadcast(broadcast);
            case SELECTED:
            case ROLE:
                return handleSelectedOrRoleBroadcast(broadcast);
            default:
                log.warn("Unhandled immediate broadcast target type: {}. Saving as is.", targetType);
                broadcast = broadcastRepository.save(broadcast);
                return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }
    }

    /**
     * Strategy: Persists a broadcast with SCHEDULED status for later processing.
     */
    private BroadcastResponse handleScheduledBroadcast(BroadcastMessage broadcast) {
        broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
        broadcast = broadcastRepository.save(broadcast);
        log.info("Broadcast ID {} has been scheduled for {}. No fan-out will occur yet.", broadcast.getId(), broadcast.getScheduledAt());
        return broadcastMapper.toBroadcastResponse(broadcast, 0);
    }

    /**
     * Strategy: Saves the broadcast as PREPARING and triggers an async event for pre-computation.
     */
    private BroadcastResponse handleProductBroadcast(BroadcastMessage broadcast) {
        log.info("Immediate PRODUCT broadcast. Saving with PREPARING status for async pre-computation.", broadcast.getId());
        broadcast.setStatus(Constants.BroadcastStatus.PREPARING.name());
        broadcast = broadcastRepository.save(broadcast);
        eventPublisher.publishEvent(new BroadcastCreatedEvent(broadcast.getId()));
        return broadcastMapper.toBroadcastResponse(broadcast, 0);
    }

    /**
     * Strategy: Fan-out on Read. Activates the broadcast and publishes a single orchestration event.
     */
    private BroadcastResponse handleAllBroadcast(BroadcastMessage broadcast) {
        log.info("Immediate ALL broadcast. Publishing single orchestration event for fan-out-on-read.");
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast = broadcastRepository.save(broadcast);
        initializeStatistics(broadcast.getId(), 0);
        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CREATED, broadcast.getContent());
        return broadcastMapper.toBroadcastResponse(broadcast, 0);
    }

    /**
     * Strategy: Early Fan-out on Write. Persists user messages and publishes an outbox event for each user.
     */
    private BroadcastResponse handleSelectedOrRoleBroadcast(BroadcastMessage broadcast) {
        log.info("Immediate {} broadcast. Using early fan-out-on-write strategy.", broadcast.getTargetType());
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast = broadcastRepository.save(broadcast);

        List<String> targetUserIds = determineTargetUsersForWrite(broadcast);
        if (!targetUserIds.isEmpty()) {
            persistUserMessages(broadcast, targetUserIds);
            publishBatchUserEvents(broadcast, targetUserIds);
        }

        return broadcastMapper.toBroadcastResponse(broadcast, targetUserIds.size());
    }

    /**
     * Helper to publish a batch of user-specific events to the outbox.
     */
    private void publishBatchUserEvents(BroadcastMessage broadcast, List<String> userIds) {
        log.info("Creating a batch of {} outbox events for broadcast ID: {}", userIds.size(), broadcast.getId());
        List<OutboxEvent> outboxEvents = new ArrayList<>();
        for (String userId : userIds) {
            MessageDeliveryEvent eventPayload = broadcastMapper.toMessageDeliveryEvent(broadcast, Constants.EventType.CREATED.name(), broadcast.getContent())
                    .toBuilder()
                    .userId(userId)
                    .build();
            outboxEvents.add(broadcastMapper.toOutboxEvent(eventPayload, appProperties.getKafka().getTopic().getNameOrchestration(), userId));
        }
        outboxEventPublisher.publishBatch(outboxEvents);
        log.info("Successfully published batch of {} events to outbox.", outboxEvents.size());
    }

    // Helper method to determine users for write strategies
    private List<String> determineTargetUsersForWrite(BroadcastMessage broadcast) {
        return switch (Constants.TargetType.valueOf(broadcast.getTargetType())) {
            // MODIFIED: Parse the JSON string back to a List
            case SELECTED -> JsonUtils.parseJsonArray(broadcast.getTargetIds());
            
            case ROLE -> JsonUtils.parseJsonArray(broadcast.getTargetIds()).stream() // MODIFIED
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
            
            case PRODUCT -> {
                // This case also needs to parse the JSON string
                yield JsonUtils.parseJsonArray(broadcast.getTargetIds()).stream() // MODIFIED
                        .flatMap(productId -> userService.getUserIdsByProduct(productId).stream())
                        .distinct()
                        .collect(Collectors.toList());
            }
            
            default -> Collections.emptyList();
        };
    }

    public void persistUserMessages(BroadcastMessage broadcast, List<String> userIds) {
        log.info("Persisting {} user_broadcast_messages records for broadcast ID: {}", userIds.size(), broadcast.getId());

        final OffsetDateTime creationTime = OffsetDateTime.now(ZoneOffset.UTC);

        List<UserBroadcastMessage> userMessages = userIds.stream()
                .map(userId -> UserBroadcastMessage.builder()
                        .broadcastId(broadcast.getId())
                        .userId(userId)
                        .deliveryStatus(Constants.DeliveryStatus.PENDING.name())
                        .readStatus(Constants.ReadStatus.UNREAD.name())
                        .createdAt(creationTime)
                        .build())
                .collect(Collectors.toList());

        userBroadcastRepository.saveAll(userMessages);
        initializeStatistics(broadcast.getId(), userIds.size());
    }

     /**
     * Activates a broadcast that has been fully prepared (i.e., its target user
     * list has already been persisted).
     *
     * This method's ONLY job is to change the status to ACTIVE and publish the
     * orchestration event to trigger the real-time delivery.
     */
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public void processReadyBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        
        // 1. Update status from READY to ACTIVE
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        broadcastRepository.save(broadcast);
        
        // 2. --- EARLY FAN-OUT LOGIC ---
        // Fetch the list of users that the async task already persisted.
        List<String> targetUserIds = userBroadcastRepository.findByBroadcastId(broadcastId).stream()
                .map(UserBroadcastMessage::getUserId)
                .collect(Collectors.toList());

        if (!targetUserIds.isEmpty()) {
            log.info("Activating PRODUCT broadcast {}. Creating a batch of {} outbox events.", broadcastId, targetUserIds.size());
            List<OutboxEvent> outboxEvents = new ArrayList<>();
            for (String userId : targetUserIds) {
                MessageDeliveryEvent eventPayload = broadcastMapper.toMessageDeliveryEvent(broadcast, Constants.EventType.CREATED.name(), broadcast.getContent())
                        .toBuilder()
                        .userId(userId) // Set the specific user ID
                        .build();
                // Partition Kafka messages by userId for better distribution
                outboxEvents.add(broadcastMapper.toOutboxEvent(eventPayload, appProperties.getKafka().getTopic().getNameOrchestration(), userId));
            }
            outboxEventPublisher.publishBatch(outboxEvents);
            log.info("Successfully published batch of {} events to outbox for broadcast {}.", outboxEvents.size(), broadcastId);
        }
    }

    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name());
        broadcastRepository.save(broadcast);

        if (!Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
            int updatedCount = userBroadcastRepository.updateNonFinalStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
            log.info("Updated {} PENDING or DELIVERED user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);
        }

        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CANCELLED, "Broadcast CANCELLED");
        
        log.info("Broadcast cancelled: {}. Published cancellation events to outbox and evicted caches.", id);
    }

    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
             .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));

        if (Constants.BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) {
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcastRepository.save(broadcast);
            
            if (!Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
                int updatedCount = userBroadcastRepository.updateNonFinalStatusesByBroadcastId(broadcastId, Constants.DeliveryStatus.SUPERSEDED.name());
                log.info("Updated {} PENDING or DELIVERED user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId);
            }
            
            publishSingleOrchestrationEvent(broadcast, Constants.EventType.EXPIRED, "Broadcast EXPIRED");
            
            log.info("Broadcast expired: {}. Published expiration events to outbox and evicted caches.", broadcastId);
        } else {
           log.info("Broadcast {} was already in a non-active state ({}). No expiration action needed.", broadcastId, broadcast.getStatus());
        }
    }
    
    private void publishSingleOrchestrationEvent(BroadcastMessage broadcast, Constants.EventType eventType, String message) {
        String topicName = appProperties.getKafka().getTopic().getNameOrchestration();
        MessageDeliveryEvent eventPayload = broadcastMapper.toMessageDeliveryEvent(broadcast, eventType.name(), message);
        outboxEventPublisher.publish(broadcastMapper.toOutboxEvent(eventPayload, topicName, broadcast.getId().toString()));
    }

    public void initializeStatistics(Long broadcastId, int totalTargeted) {
        BroadcastStatistics stats = BroadcastStatistics.builder()
                .broadcastId(broadcastId)
                .totalTargeted(totalTargeted)
                .totalDelivered(0)
                .totalRead(0)
                .totalFailed(0)
                .calculatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        broadcastStatisticsRepository.save(stats);
    }

    private MessageDeliveryEvent createOrchestrationEvent(MessageDeliveryEvent deliveryEvent, Constants.EventType eventType, String message) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(deliveryEvent.getBroadcastId())
                .eventType(eventType.name())
                .timestampEpochMilli(OffsetDateTime.now(ZoneOffset.UTC).toEpochSecond())
                .message(message)
                .build();
    }

    @Transactional
    public void activateAndPublishFanOutOnReadBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found: " + broadcastId));

        // Directly update status to ACTIVE
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcastRepository.save(broadcast);

        // Publish the single orchestration event to the outbox to start the fan-out-on-read process
        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CREATED, broadcast.getContent());
        log.info("Activated scheduled fan-out-on-read broadcast ID: {}", broadcastId);
    }

    /**
     * Activates a broadcast and publishes a specific outbox event for each targeted user.
     * This is used for "fan-out-on-write" broadcasts (i.e., scheduled 'SELECTED' and 'ROLE').
     * @param broadcast The broadcast message to activate.
     */
    @Transactional
    public void activateAndPublishFanOutOnWriteBroadcast(BroadcastMessage broadcast) {
        // 1. Update the broadcast status to ACTIVE.
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcast.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        broadcastRepository.save(broadcast);

        // 2. Fetch the list of user messages that were already persisted by the scheduler.
        List<String> targetUserIds = userBroadcastRepository.findByBroadcastId(broadcast.getId()).stream()
                .map(UserBroadcastMessage::getUserId)
                .collect(Collectors.toList());
        
        if (targetUserIds.isEmpty()) {
            log.warn("Activated broadcast {} but found no persisted user messages to create outbox events for.", broadcast.getId());
            return;
        }

        // 3. Create a batch of user-specific outbox events.
        log.info("Activating broadcast {}. Creating a batch of {} outbox events for early fan-out.", broadcast.getId(), targetUserIds.size());
        List<OutboxEvent> outboxEvents = new ArrayList<>();
        for (String userId : targetUserIds) {
            MessageDeliveryEvent eventPayload = broadcastMapper.toMessageDeliveryEvent(broadcast, Constants.EventType.CREATED.name(), broadcast.getContent())
                    .toBuilder()
                    .userId(userId)
                    .build();
            // Use userId as the Kafka key for partitioning
            outboxEvents.add(broadcastMapper.toOutboxEvent(eventPayload, appProperties.getKafka().getTopic().getNameOrchestration(), userId));
        }
        
        // 4. Publish the entire batch to the outbox in one go.
        outboxEventPublisher.publishBatch(outboxEvents);
        log.info("Successfully published batch of {} events to outbox for activated broadcast {}.", outboxEvents.size(), broadcast.getId());
    }

    @Transactional
    public void failBroadcast(MessageDeliveryEvent deliveryEvent) {
        if (deliveryEvent.getBroadcastId() == null) return;
        broadcastRepository.updateStatus(deliveryEvent.getBroadcastId(), Constants.BroadcastStatus.FAILED.name());
        String topicName = appProperties.getKafka().getTopic().getNameOrchestration();
        MessageDeliveryEvent eventPayload = createOrchestrationEvent(deliveryEvent, Constants.EventType.FAILED, "Broadcast FAILED");
        outboxEventPublisher.publish(broadcastMapper.toOutboxEvent(eventPayload, topicName, deliveryEvent.getBroadcastId().toString()));
        log.warn("Marked entire BroadcastMessage {} as FAILED in a new transaction and evicted cache.", deliveryEvent.getBroadcastId());
    }

}