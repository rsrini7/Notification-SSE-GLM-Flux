package com.example.broadcast.service;

import com.example.broadcast.dto.BroadcastRequest;
import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.BroadcastStatistics;
import com.example.broadcast.model.OutboxEvent;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.example.broadcast.repository.OutboxRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.repository.UserPreferencesRepository;
import com.example.broadcast.exception.ResourceNotFoundException;
import com.example.broadcast.exception.UserServiceUnavailableException;
import com.example.broadcast.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.example.broadcast.util.Constants.BroadcastStatus;
import com.example.broadcast.util.Constants.DeliveryStatus;
import com.example.broadcast.util.Constants.EventType;
import com.example.broadcast.config.AppProperties;


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
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final TestingConfigurationService testingConfigService;

    private final AppProperties appProperties;

    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public BroadcastResponse createBroadcast(BroadcastRequest request) {
        log.info("Creating broadcast from sender: {}, target: {}", request.getSenderId(), request.getTargetType());
        BroadcastMessage broadcast = BroadcastMessage.builder()
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
        
        if (broadcast.getExpiresAt() != null && broadcast.getExpiresAt().isBefore(ZonedDateTime.now(ZoneOffset.UTC))) {
            log.warn("Broadcast creation request for an already expired message. Expiration: {}", broadcast.getExpiresAt());
            broadcast.setStatus(BroadcastStatus.EXPIRED.name());
            broadcast = broadcastRepository.save(broadcast);
            return buildBroadcastResponse(broadcast, 0);
        }

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(ZonedDateTime.now(ZoneOffset.UTC))) {
            broadcast.setStatus(BroadcastStatus.SCHEDULED.name());
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast with ID: {} is scheduled for: {}", broadcast.getId(), broadcast.getScheduledAt());
            return buildBroadcastResponse(broadcast, 0);
        } else {
            broadcast.setStatus(BroadcastStatus.ACTIVE.name());
            broadcast = broadcastRepository.save(broadcast);
            return triggerBroadcast(broadcast);
        }
    }

    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public void processScheduledBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        
        broadcast.setStatus(BroadcastStatus.ACTIVE.name());
        broadcast.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
        broadcastRepository.update(broadcast);

        triggerBroadcast(broadcast);
    }

    private BroadcastResponse triggerBroadcast(BroadcastMessage broadcast) {
        boolean shouldFail = testingConfigService.isKafkaConsumerFailureEnabled();
        if (shouldFail) {
            log.info("Kafka failure mode is enabled. This broadcast will be marked for transient failure.");
            testingConfigService.setKafkaConsumerFailureEnabled(false);
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
                    .eventType(EventType.CREATED.name())
                    .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                    .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                    .message(broadcast.getContent())
                    .transientFailure(shouldFail)
                    .build();
                
                saveToOutbox(eventPayload, topicName);
            }
        } else {
            log.warn("Broadcast {} created, but no users were targeted after filtering.", broadcast.getId());
        }
        
        return buildBroadcastResponse(broadcast, totalTargeted);
    }
    
    private void saveToOutbox(MessageDeliveryEvent payload, String topicName) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("broadcast")
                    .aggregateId(payload.getUserId())
                    .eventType(payload.getEventType())
                    .topic(topicName) // Set the topic here
                    .payload(payloadJson)
                    .build();
            outboxRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event payload for outbox", e);
            throw new RuntimeException("Failed to serialize event payload", e);
        }
    }
    
    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        
        broadcast.setStatus(BroadcastStatus.CANCELLED.name());
        broadcastRepository.update(broadcast);
        
        int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(id, DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} pending user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);

        String topicName = Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) ? appProperties.getKafka().getTopic().getNameAll() : appProperties.getKafka().getTopic().getNameSelected();
        List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(id);
        for (UserBroadcastMessage userMessage : userBroadcasts) {
            MessageDeliveryEvent eventPayload = MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .userId(userMessage.getUserId())
                .eventType(EventType.CANCELLED.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message("Broadcast CANCELLED")
                .build();
            saveToOutbox(eventPayload, topicName);
        }
        log.info("Broadcast cancelled: {}", id);
    }

    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));
        
        if (BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) {
            broadcast.setStatus(BroadcastStatus.EXPIRED.name());
            broadcastRepository.update(broadcast);

            int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(broadcastId, DeliveryStatus.SUPERSEDED.name());
            log.info("Updated {} pending user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId);

            log.info("Broadcast expired: {}", broadcastId);
            String topicName = Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) ? appProperties.getKafka().getTopic().getNameAll() : appProperties.getKafka().getTopic().getNameSelected();
            List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(broadcastId);
            for (UserBroadcastMessage userMessage : userBroadcasts) {
                MessageDeliveryEvent eventPayload = MessageDeliveryEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .broadcastId(broadcast.getId())
                    .userId(userMessage.getUserId())
                    .eventType(EventType.EXPIRED.name())
                    .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                    .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                    .message("Broadcast EXPIRED")
                    .build();
                saveToOutbox(eventPayload, topicName);
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

    private BroadcastResponse buildBroadcastResponse(BroadcastMessage broadcast, int totalTargeted) {
        return BroadcastResponse.builder()
                .id(broadcast.getId())
                .senderId(broadcast.getSenderId())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .targetType(broadcast.getTargetType())
                .targetIds(broadcast.getTargetIds())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .expiresAt(broadcast.getExpiresAt())
                .createdAt(broadcast.getCreatedAt())
                .scheduledAt(broadcast.getScheduledAt())
                .status(broadcast.getStatus())
                .totalTargeted(totalTargeted)
                .totalDelivered(0)
                .totalRead(0)
                .build();
    }
    
    public List<UserBroadcastMessage> getBroadcastDeliveries(Long broadcastId) {
        log.info("Retrieving delivery details for broadcast ID: {}", broadcastId);
        return userBroadcastRepository.findByBroadcastId(broadcastId);
    }
}