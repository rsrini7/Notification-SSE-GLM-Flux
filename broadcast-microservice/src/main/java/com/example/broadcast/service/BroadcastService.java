package com.example.broadcast.service;

import com.example.broadcast.config.AppProperties;
import com.example.broadcast.dto.BroadcastRequest;
import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.exception.UserServiceUnavailableException;
import com.example.broadcast.mapper.BroadcastMapper;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.BroadcastStatistics;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.repository.UserPreferencesRepository;
import com.example.broadcast.util.Constants;
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
public class BroadcastService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final BroadcastTargetingService broadcastTargetingService;
    private final UserPreferencesRepository userPreferencesRepository;
    private final UserService userService;
    private final OutboxEventPublisher outboxEventPublisher;
    private final TestingConfigurationService testingConfigService;
    private final BroadcastMapper broadcastMapper;
    private final AppProperties appProperties;

    /**
     * Creates a new broadcast. If it's scheduled for the future, it's saved with a SCHEDULED status.
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
            return broadcastMapper.toBroadcastResponse(broadcast, 0);
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
        log.info("Processing scheduled broadcast with ID: {}", broadcastId);
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new IllegalStateException("Scheduled broadcast not found with ID: " + broadcastId)); // Should not happen if locked
        
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
        boolean shouldFail = testingConfigService.isKafkaConsumerFailureEnabled(); 
        if (shouldFail) {
            log.warn("Kafka failure mode is enabled. The next created broadcast will be marked for transient failure.");
            testingConfigService.setKafkaConsumerFailureEnabled(false); 
        }

        List<UserBroadcastMessage> userBroadcasts = broadcastTargetingService.createUserBroadcastMessagesForBroadcast(broadcast);
        int totalTargeted = userBroadcasts.size(); 

        if (totalTargeted > 0) {
            log.info("Broadcast {} targeting {} users.", broadcast.getId(), totalTargeted);
            
            // Create initial statistics record
            BroadcastStatistics initialStats = BroadcastStatistics.builder()
                    .broadcastId(broadcast.getId())
                    .totalTargeted(totalTargeted)
                    .totalDelivered(0)
                    .totalRead(0)
                    .totalFailed(0) 
                    .calculatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .build();
            broadcastStatisticsRepository.save(initialStats); 

            // Batch insert all user-specific message records
            userBroadcastRepository.batchInsert(userBroadcasts);

            // Publish a CREATED event for each user to the outbox
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
                    .transientFailure(shouldFail)
                    .build();
                
                outboxEventPublisher.publish(eventPayload, topicName);
            }
        } else {
            log.warn("Broadcast {} created, but no users were targeted after filtering.", broadcast.getId());
        }
        
        return broadcastMapper.toBroadcastResponse(broadcast, totalTargeted);
    }

    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackGetAllUserIds")
    public List<String> getAllUserIds() {
        log.info("Retrieving all unique user IDs from the authoritative user service.");
        return userService.getAllUserIds(); 
    }

    public List<String> fallbackGetAllUserIds(Throwable t) {
        log.warn("UserService is unavailable, falling back to user preferences for the user list. Error: {}", t.getMessage()); 
        throw new UserServiceUnavailableException("User service is unavailable, cannot determine target users.");
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