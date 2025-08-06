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
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.repository.UserPreferencesRepository;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.service.UserService;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Refactored service responsible for the COMMAND side of broadcasts.
 * Its primary responsibilities are creating new broadcasts and processing scheduled ones.
 * It delegates lifecycle management and querying to other dedicated services.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastCreationService {


    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;

    private final BroadcastTargetingService broadcastTargetingService;
    private final UserPreferencesRepository userPreferencesRepository;
    private final UserService userService;
    private final TestingConfigurationService testingConfigurationService;
    private final AppProperties appProperties;
    // --- REFACTORED DEPENDENCIES ---
    private final OutboxEventPublisher outboxEventPublisher;
    private final BroadcastMapper broadcastMapper;
    /**
     * Creates a new broadcast.
     * If it's scheduled for the future, it's saved with a SCHEDULED status.
     * Otherwise, it's processed immediately.
     *
     * @param request The broadcast creation request DTO.
     * @return A response DTO for the newly created broadcast.
     */
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public BroadcastResponse createBroadcast(BroadcastRequest request) {
        log.info("Creating broadcast from sender: {}, target: {}", request.getSenderId(), request.getTargetType());
        BroadcastMessage broadcast = buildBroadcastFromRequest(request);

        if (broadcast.getExpiresAt() != null && broadcast.getExpiresAt().isBefore(ZonedDateTime.now(ZoneOffset.UTC))) {
            log.warn("Broadcast creation request for an already expired message. Expiration: {}", broadcast.getExpiresAt());
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcast = broadcastRepository.save(broadcast);
            return broadcastMapper.toBroadcastResponse(broadcast, 0); // REFACTORED
        }

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(ZonedDateTime.now(ZoneOffset.UTC))) {
            broadcast.setStatus(Constants.BroadcastStatus.SCHEDULED.name());
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast with ID: {} is scheduled for: {}", broadcast.getId(), broadcast.getScheduledAt());
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
        } else {
            broadcast.setStatus(Constants.BroadcastStatus.ACTIVE.name());
            broadcast = broadcastRepository.save(broadcast);
            return triggerBroadcast(broadcast);
        }
    }

    /**
     * Processes a previously scheduled broadcast, making it active and triggering delivery.
     *
     * @param broadcastId The ID of the scheduled broadcast to process.
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
     * The core logic for initiating a broadcast: targets users, creates statistics,
     * persists user-specific messages, and publishes events to the outbox.
     *
     * @param broadcast The broadcast message to trigger.
     * @return A response DTO for the triggered broadcast.
     */
    private BroadcastResponse triggerBroadcast(BroadcastMessage broadcast) {
        boolean shouldFail = testingConfigurationService.isKafkaConsumerFailureEnabled();
        if (shouldFail) {
            log.info("Kafka failure mode is enabled. This broadcast will be marked for transient failure.");
            testingConfigurationService.setKafkaConsumerFailureEnabled(false);
        }

        List<UserBroadcastMessage> userBroadcasts = broadcastTargetingService.createUserBroadcastMessagesForBroadcast(broadcast);
        int totalTargeted = userBroadcasts.size();
        if (totalTargeted > 0) {
            log.info("Broadcast {} targeting {} users.", broadcast.getId(), totalTargeted);
            BroadcastStatistics initialStats = BroadcastStatistics.builder()
                    .broadcastId(broadcast.getId())
                    .totalTargeted(totalTargeted)
                    .totalDelivered(0)
                    .totalRead(0)
                    .totalFailed(0)
                    .calculatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .build();
            broadcastStatisticsRepository.save(initialStats);
            userBroadcastRepository.batchInsert(userBroadcasts);

            String topicName = Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) ? appProperties.getKafka().getTopic().getNameAll() : appProperties.getKafka().getTopic().getNameSelected();
            for (UserBroadcastMessage userMessage : userBroadcasts) { 
                MessageDeliveryEvent eventPayload = MessageDeliveryEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .broadcastId(broadcast.getId())
                    .userId(userMessage.getUserId())
                    .eventType(Constants.EventType.CREATED.name())
                    .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                    .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                    .message(broadcast.getContent())
                    .build(); 
                outboxEventPublisher.publish(eventPayload, eventPayload.getUserId(), eventPayload.getEventType(), topicName); 
            }
        } else {
            log.warn("Broadcast {} created, but no users were targeted after filtering.", broadcast.getId()); 
        }
        
        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted); 
    }
    
    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name()); 
        broadcastRepository.update(broadcast);
        
        int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} pending user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id); 
        String topicName = Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) ? appProperties.getKafka().getTopic().getNameAll() : appProperties.getKafka().getTopic().getNameSelected();
        List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(id); 
        for (UserBroadcastMessage userMessage : userBroadcasts) {
            MessageDeliveryEvent eventPayload = MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .userId(userMessage.getUserId())
                .eventType(Constants.EventType.CANCELLED.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local")) 
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message("Broadcast CANCELLED")
                .build();
            outboxEventPublisher.publish(eventPayload, eventPayload.getUserId(), eventPayload.getEventType(), topicName); 
        }
        log.info("Broadcast cancelled: {}", id); 
    }

    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        if (Constants.BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) { 
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name());
            broadcastRepository.update(broadcast);
            int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(broadcastId, Constants.DeliveryStatus.SUPERSEDED.name()); 
            log.info("Updated {} pending user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId);
            log.info("Broadcast expired: {}", broadcastId); 
            String topicName = Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) ? appProperties.getKafka().getTopic().getNameAll() : appProperties.getKafka().getTopic().getNameSelected();
            List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(broadcastId); 
            for (UserBroadcastMessage userMessage : userBroadcasts) {
                MessageDeliveryEvent eventPayload = MessageDeliveryEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .broadcastId(broadcast.getId())
                    .userId(userMessage.getUserId())
                    .eventType(Constants.EventType.EXPIRED.name()) 
                    .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                    .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                    .message("Broadcast EXPIRED")
                    .build();
                outboxEventPublisher.publish(eventPayload, eventPayload.getUserId(), eventPayload.getEventType(), topicName); 
            }
        }
    }
    
    public BroadcastResponse getBroadcast(Long id) {
        return broadcastRepository.findBroadcastWithStatsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id)); 
    }

    public List<BroadcastResponse> getActiveBroadcasts() {
        return broadcastRepository.findActiveBroadcastsWithStats(); 
    }
    
    public List<BroadcastResponse> getScheduledBroadcasts() {
        return broadcastRepository.findScheduledBroadcastsWithStats(); 
    }

    public List<BroadcastResponse> getAllBroadcasts() {
        return broadcastRepository.findAllBroadcastsWithStats(); 
    }

    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackGetAllUserIds")
    public List<String> getAllUserIds() {
        log.info("Retrieving all unique user IDs from the authoritative user service.");
        return userService.getAllUserIds(); 
    }

    public List<String> fallbackGetAllUserIds(Throwable t) {
        log.warn("UserService is unavailable, falling back to user preferences for the user list. Error: {}", t.getMessage());
        return userPreferencesRepository.findAllUserIds(); 
    }
    
    public List<UserBroadcastMessage> getBroadcastDeliveries(Long broadcastId) {
        log.info("Retrieving delivery details for broadcast ID: {}", broadcastId);
        return userBroadcastRepository.findByBroadcastId(broadcastId); 
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
                .build();
    }
}