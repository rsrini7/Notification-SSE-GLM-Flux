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
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.*;
import com.example.broadcast.shared.service.OutboxEventPublisher;
// import com.example.broadcast.shared.service.TestingConfigurationService;
// import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.admin.event.BroadcastCreatedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastLifecycleService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final UserBroadcastTargetRepository userBroadcastTargetRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final BroadcastMapper broadcastMapper;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    // private final CacheService cacheService;
    // private final TestingConfigurationService testingConfigurationService;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public BroadcastResponse createBroadcast(BroadcastRequest request) {
        // boolean isFailureTest = testingConfigurationService.consumeArmedState();
        log.info("Creating broadcast from sender: {}, target: {}", request.getSenderId(), request.getTargetType());
        BroadcastMessage broadcast = buildBroadcastFromRequest(request);

        if (broadcast.getExpiresAt() != null && broadcast.getExpiresAt().isBefore(ZonedDateTime.now(ZoneOffset.UTC))) {
            log.warn("Broadcast creation request for an already expired message. Expiration: {}", broadcast.getExpiresAt());
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcast = broadcastRepository.save(broadcast);
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        }
       
        //cacheService.evictBroadcastContent(broadcast.getId());
        // log.info("Evicted broadcast-content cache for ID: {}", broadcast.getId());
      
        // ONLY the 'PRODUCT' type requires the intensive user list preparation (Fan-out-on-Write).
        if (Constants.TargetType.PRODUCT.name().equals(request.getTargetType())) {
            log.info("Broadcast is for PRODUCT users. Saving with PREPARING status to trigger user pre-computation.", broadcast.getId());
            broadcast.setStatus(Constants.BroadcastStatus.PREPARING.name());
            broadcast = broadcastRepository.save(broadcast);
            
            eventPublisher.publishEvent(new BroadcastCreatedEvent(broadcast.getId()));
            return broadcastMapper.toBroadcastResponse(broadcast, 0); // Target count is unknown until preparation is complete.
        }

        // ALL, ROLE, and SELECTED are handled as immediate Fan-out-on-Read types.
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        long fetchDelayMs = appProperties.getSimulation().getUserFetchDelayMs();
        ZonedDateTime precomputationThreshold = now.plus(fetchDelayMs, ChronoUnit.MILLIS);
        boolean isImmediatelyActive = true;

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(precomputationThreshold)) {
            broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
            isImmediatelyActive = false; // It's not active yet, the scheduler will handle it.
        } else {
            broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        }
        
        broadcast = broadcastRepository.save(broadcast);

        // if (isFailureTest) {
        //     testingConfigurationService.markBroadcastForFailure(broadcast.getId());
        //     log.warn("Broadcast ID {} has been marked for DLT failure simulation.", broadcast.getId());
        // }

        // If it's an immediate broadcast, we MUST publish the single orchestration event to the outbox.
        // This now correctly includes ALL, ROLE, and SELECTED types.
        if (isImmediatelyActive) {
            publishSingleOrchestrationEvent(broadcast, Constants.EventType.CREATED, broadcast.getContent());
        }

        // This is for display purposes only on the API response.
        int totalTargeted = Constants.TargetType.SELECTED.name().equals(request.getTargetType()) ? request.getTargetIds().size() : 0;
        
        // Evict cache so newly connecting users see the latest group broadcasts.
        // cacheService.evictActiveGroupBroadcastsCache();
        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CACHE_EVICT_BROADCAST_ACTIVEGROUP, "Broadcast Cancel CACHE_EVICT_BROADCAST_ACTIVEGROUP");

        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted);
    }

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

    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name());
        broadcastRepository.update(broadcast);

        int updatedCount = userBroadcastRepository.updateNonFinalStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} PENDING or DELIVERED user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);

        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CANCELLED, "Broadcast CANCELLED");
        
        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CACHE_EVICT_BROADCAST_ACTIVEGROUP, "Broadcast Cancel CACHE_EVICT_BROADCAST_ACTIVEGROUP");

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
            
            publishSingleOrchestrationEvent(broadcast, Constants.EventType.EXPIRED, "Broadcast EXPIRED");
            
            publishSingleOrchestrationEvent(broadcast, Constants.EventType.CACHE_EVICT_BROADCAST_ACTIVEGROUP, "Broadcast Expired CACHE_EVICT_BROADCAST_ACTIVEGROUP");

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
        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CREATED, broadcast.getContent());
        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted);
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
                .podId(System.getenv().getOrDefault("POD_NAME", "broadcast-user-service-0"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(message)
                .build();
    }

    private MessageDeliveryEvent createOrchestrationEvent(MessageDeliveryEvent deliveryEvent, Constants.EventType eventType, String message) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(deliveryEvent.getBroadcastId())
                .eventType(eventType.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "broadcast-user-service-0"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(message)
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


    @Transactional
    public void activateAndPublishFanOutOnReadBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found: " + broadcastId));

        // Directly update status to ACTIVE
        broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
        broadcastRepository.update(broadcast);

        // Publish the single orchestration event to the outbox to start the fan-out-on-read process
        publishSingleOrchestrationEvent(broadcast, Constants.EventType.CREATED, broadcast.getContent());
        log.info("Activated scheduled fan-out-on-read broadcast ID: {}", broadcastId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failBroadcast(MessageDeliveryEvent deliveryEvent) {
        if (deliveryEvent.getBroadcastId() == null) return;
        broadcastRepository.updateStatus(deliveryEvent.getBroadcastId(), Constants.BroadcastStatus.FAILED.name());
        String topicName = appProperties.getKafka().getTopic().getNameOrchestration();
        MessageDeliveryEvent eventPayload = createOrchestrationEvent(deliveryEvent, Constants.EventType.CACHE_EVICT_ACTIVEGROUP, "Broadcast CACHE_EVICT_ACTIVEGROUP");
        outboxEventPublisher.publish(createOutboxEvent(eventPayload, topicName, deliveryEvent.getBroadcastId().toString()));
        log.warn("Marked entire BroadcastMessage {} as FAILED in a new transaction and evicted cache.", deliveryEvent.getBroadcastId());
    }


}