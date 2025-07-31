package com.example.broadcast.service;

import com.example.broadcast.dto.BroadcastRequest;
import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.BroadcastStatistics;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final BroadcastTargetingService broadcastTargetingService;

    @Value("${broadcast.kafka.topic.name:broadcast-events}")
    private String kafkaTopic;

    @Transactional
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

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(ZonedDateTime.now(ZoneOffset.UTC))) {
            broadcast.setStatus("SCHEDULED");
            broadcast = broadcastRepository.save(broadcast);
            log.info("Broadcast with ID: {} is scheduled for: {}", broadcast.getId(), broadcast.getScheduledAt());
            return buildBroadcastResponse(broadcast, 0);
        } else {
            broadcast.setStatus("ACTIVE");
            broadcast = broadcastRepository.save(broadcast);
            return triggerBroadcast(broadcast);
        }
    }

    @Transactional
    public void processScheduledBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + broadcastId));
        
        broadcast.setStatus("ACTIVE");
        broadcast.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
        broadcastRepository.updateStatus(broadcast.getId(), "ACTIVE");

        triggerBroadcast(broadcast);
    }

    private BroadcastResponse triggerBroadcast(BroadcastMessage broadcast) {
        List<UserBroadcastMessage> userBroadcasts = broadcastTargetingService.createUserBroadcastMessagesForBroadcast(broadcast);
        int totalTargeted = userBroadcasts.size();

        BroadcastStatistics initialStats = BroadcastStatistics.builder()
                .broadcastId(broadcast.getId())
                .totalTargeted(totalTargeted)
                .totalDelivered(0)
                .totalRead(0)
                .totalFailed(0)
                .calculatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build();
        broadcastStatisticsRepository.save(initialStats);

        if (!userBroadcasts.isEmpty()) {
            userBroadcastRepository.batchInsert(userBroadcasts);
        }

        List<String> targetUserIds = userBroadcasts.stream()
            .map(UserBroadcastMessage::getUserId)
            .collect(Collectors.toList());

        sendBroadcastEvent(broadcast, targetUserIds, "CREATED");
        log.info("Broadcast triggered successfully with ID: {}, targeting {} users", broadcast.getId(), totalTargeted);
        return buildBroadcastResponse(broadcast, totalTargeted);
    }

    public BroadcastResponse getBroadcast(Long id) {
        return broadcastRepository.findBroadcastWithStatsById(id)
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + id));
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

    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + id));
        
        broadcastRepository.updateStatus(id, "CANCELLED");
        
        List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(id);
        List<String> targetUsers = userBroadcasts.stream()
                .map(UserBroadcastMessage::getUserId)
                .distinct()
                .collect(Collectors.toList());
        
        sendBroadcastEvent(broadcast, targetUsers, "CANCELLED");
        log.info("Broadcast cancelled: {}", id);
    }

    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + broadcastId));

        if ("ACTIVE".equals(broadcast.getStatus())) {
            broadcastRepository.updateStatus(broadcastId, "EXPIRED");
            log.info("Broadcast expired: {}", broadcastId);

            List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(broadcastId);
            List<String> targetUsers = userBroadcasts.stream()
                    .map(UserBroadcastMessage::getUserId)
                    .distinct()
                    .collect(Collectors.toList());

            sendBroadcastEvent(broadcast, targetUsers, "EXPIRED");
        }
    }

    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);
        List<UserBroadcastMessage> userMessages = userBroadcastRepository.findByUserId(userId);
        return userMessages.stream()
                .map(this::buildUserBroadcastResponse)
                .collect(Collectors.toList());
    }

    public List<UserBroadcastResponse> getUnreadMessages(String userId) {
        log.info("Getting unread messages for user: {}", userId);
        List<UserBroadcastMessage> unreadMessages = userBroadcastRepository.findUnreadByUserId(userId);
        return unreadMessages.stream()
                .map(this::buildUserBroadcastResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public void markMessageAsRead(String userId, Long messageId) {
        log.info("Marking message as read: user={}, message={}", userId, messageId);
        
        UserBroadcastMessage userMessage = userBroadcastRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("User message not found: " + messageId));
        
        if (!userId.equals(userMessage.getUserId())) {
            throw new RuntimeException("Message does not belong to user: " + userId);
        }
        
        userBroadcastRepository.markAsRead(messageId, ZonedDateTime.now(ZoneOffset.UTC));
        log.info("Message marked as read: user={}, message={}", userId, messageId);
    }

    private void sendBroadcastEvent(BroadcastMessage broadcast, List<String> targetUsers, String eventType) {
        targetUsers.forEach(userId -> {
            MessageDeliveryEvent event = MessageDeliveryEvent.builder()
                    .eventId(UUID.randomUUID().toString())
                    .broadcastId(broadcast.getId())
                    .userId(userId)
                    .eventType(eventType)
                    .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                    .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                    .message("Broadcast " + eventType.toLowerCase())
                    .build();
            
            kafkaTemplate.send(kafkaTopic, userId, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.debug("Event sent successfully to Kafka: {}", event.getEventId());
                    } else {
                        log.error("Failed to send event to Kafka: {}", ex.getMessage());
                    }
                });
        });
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

    private UserBroadcastResponse buildUserBroadcastResponse(UserBroadcastMessage userMessage) {
        BroadcastMessage broadcast = broadcastRepository.findById(userMessage.getBroadcastId())
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + userMessage.getBroadcastId()));
        
        return UserBroadcastResponse.builder()
                .id(userMessage.getId())
                .broadcastId(userMessage.getBroadcastId())
                .userId(userMessage.getUserId())
                .deliveryStatus(userMessage.getDeliveryStatus())
                .readStatus(userMessage.getReadStatus())
                .deliveredAt(userMessage.getDeliveredAt())
                .readAt(userMessage.getReadAt())
                .createdAt(userMessage.getCreatedAt())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .broadcastCreatedAt(broadcast.getCreatedAt())
                .expiresAt(broadcast.getExpiresAt())
                .build();
    }

    /**
     * **NEW:** Retrieves the delivery details for a specific broadcast.
     * @param broadcastId The ID of the broadcast.
     * @return A list of user-specific message details.
     */
    public List<UserBroadcastMessage> getBroadcastDeliveries(Long broadcastId) {
        log.info("Retrieving delivery details for broadcast ID: {}", broadcastId);
        return userBroadcastRepository.findByBroadcastId(broadcastId);
    }
}
