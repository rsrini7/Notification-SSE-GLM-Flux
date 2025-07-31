package com.example.broadcast.service;

import com.example.broadcast.dto.BroadcastRequest;
import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.model.BroadcastStatistics;
import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.model.UserPreferences;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.repository.UserPreferencesRepository;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Service for managing broadcast messages
 * Handles creation, delivery, and tracking of broadcast messages
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    
    @Value("${broadcast.kafka.topic.name:broadcast-events}")
    private String kafkaTopic;

    /**
     * Create a new broadcast message
     * This is the main entry point for administrators to send broadcasts
     */
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
        List<String> targetUsers = determineTargetUsers(broadcast);

        BroadcastStatistics initialStats = BroadcastStatistics.builder()
                .broadcastId(broadcast.getId())
                .totalTargeted(targetUsers.size())
                .totalDelivered(0)
                .totalRead(0)
                .totalFailed(0)
                .calculatedAt(ZonedDateTime.now(ZoneOffset.UTC).toInstant().atZone(ZoneOffset.UTC))
                .build();
        broadcastStatisticsRepository.save(initialStats);
        List<UserBroadcastMessage> userBroadcasts = createUserBroadcastMessages(broadcast.getId(), targetUsers);

        if (!userBroadcasts.isEmpty()) {
            userBroadcastRepository.batchInsert(userBroadcasts);
        }

        sendBroadcastEvent(broadcast, targetUsers, "CREATED");

        log.info("Broadcast triggered successfully with ID: {}, targeting {} users", broadcast.getId(), targetUsers.size());

        return buildBroadcastResponse(broadcast, targetUsers.size());
    }

    /**
     * Determine target users based on broadcast request
     */
    private List<String> determineTargetUsers(BroadcastMessage broadcast) {
        // In a real implementation, this would query user service or database
        // For now, we'll return sample user IDs based on target type
        
        if ("ALL".equals(broadcast.getTargetType())) {
            // Return all active users (in production, this would query user service)
            return List.of("user-001", "user-002", "user-003", "user-004", "user-005");
        } else if ("SELECTED".equals(broadcast.getTargetType()) && broadcast.getTargetIds() != null) {
            // Return specifically selected users
            return broadcast.getTargetIds();
        } else if ("ROLE".equals(broadcast.getTargetType()) && broadcast.getTargetIds() != null) {
            // Return users with specified roles (in production, this would query user service)
            return List.of("user-001", "user-002", "user-003");
        }
        
        return List.of();
    }

    /**
     * Create user broadcast messages for target users
     */
    private List<UserBroadcastMessage> createUserBroadcastMessages(Long broadcastId, List<String> targetUsers) {
        List<UserBroadcastMessage> userBroadcasts = new ArrayList<>();
        
        for (String userId : targetUsers) {
            // Check user preferences before creating message
            UserPreferences preferences = userPreferencesRepository.findByUserId(userId).orElse(null);
            
            if (shouldDeliverToUser(preferences)) {
                UserBroadcastMessage userBroadcast = UserBroadcastMessage.builder()
                        .broadcastId(broadcastId)
                        .userId(userId)
                        .deliveryStatus("PENDING")
                        .readStatus("UNREAD")
                        .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                        .updatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                        .build();
                
                userBroadcasts.add(userBroadcast);
            }
        }
        
        return userBroadcasts;
    }

    /**
     * Check if message should be delivered to user based on preferences
     */
    private boolean shouldDeliverToUser(UserPreferences preferences) {
        if (preferences == null) {
            return true; // Default to deliver if no preferences set
        }
        
        if (!preferences.getNotificationEnabled()) {
            return false;
        }
        
        // Check quiet hours (simplified - in production, would consider user timezone)
        if (preferences.getQuietHoursStart() != null && preferences.getQuietHoursEnd() != null) {
            java.time.LocalTime now = java.time.LocalTime.now();
            java.time.LocalTime start = preferences.getQuietHoursStart();
            java.time.LocalTime end = preferences.getQuietHoursEnd();
            
            if (isInQuietHours(now, start, end)) {
                return false;
            }
        }
        
        return true;
    }

    /**
     * Check if current time is within quiet hours
     */
    private boolean isInQuietHours(java.time.LocalTime now, java.time.LocalTime start, java.time.LocalTime end) {
        if (start.isBefore(end)) {
            return !now.isBefore(start) && !now.isAfter(end);
        } else {
            // Handle overnight quiet hours (e.g., 22:00 to 06:00)
            return !now.isBefore(start) || !now.isAfter(end);
        }
    }

    /**
     * Send broadcast event to Kafka
     */
    private void sendBroadcastEvent(BroadcastMessage broadcast, List<String> targetUsers, String eventType) {
        try {
            for (String userId : targetUsers) {
                MessageDeliveryEvent event = MessageDeliveryEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .broadcastId(broadcast.getId())
                        .userId(userId)
                        .eventType(eventType)
                        .podId(getCurrentPodId())
                        .timestamp(ZonedDateTime.now(ZoneOffset.UTC).toInstant().atZone(ZoneOffset.UTC))
                        .message("Broadcast " + eventType.toLowerCase())
                        .build();
                
                // Send event to Kafka with user ID as key for partitioning
                CompletableFuture<Void> future = kafkaTemplate.send(kafkaTopic, userId, event)
                        .thenAccept(result -> {
                            log.debug("Event sent successfully to Kafka: {}", event.getEventId());
                        })
                        .exceptionally(ex -> {
                            log.error("Failed to send event to Kafka: {}", ex.getMessage());
                            return null;
                        });
                
                // For high-priority messages, wait for send completion
                if ("URGENT".equals(broadcast.getPriority()) || "HIGH".equals(broadcast.getPriority())) {
                    future.join();
                }
            }
        } catch (Exception e) {
            log.error("Error sending broadcast event to Kafka: {}", e.getMessage());
        }
    }

    /**
     * Get current pod ID (in Kubernetes, this would be the pod name)
     */
    private String getCurrentPodId() {
        // In production, this would be retrieved from environment variables
        return System.getenv().getOrDefault("POD_NAME", "pod-local");
    }

    /**
     * Build broadcast response
     */
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
                .status(broadcast.getStatus())
                .totalTargeted(totalTargeted)
                .totalDelivered(0) // Will be updated as messages are delivered
                .totalRead(0) // Will be updated as messages are read
                .build();
    }

    /**
     * Get broadcast by ID
     */
    public BroadcastResponse getBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + id));
        
        // Get statistics from dedicated repository
        BroadcastStatistics stats = broadcastStatisticsRepository.findByBroadcastId(id)
                .orElseGet(() -> BroadcastStatistics.builder()
                        .broadcastId(id)
                        .totalTargeted(0)
                        .totalDelivered(0)
                        .totalRead(0)
                        .build());
        
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
                .status(broadcast.getStatus())
                .totalTargeted(stats.getTotalTargeted())
                .totalDelivered(stats.getTotalDelivered())
                .totalRead(stats.getTotalRead())
                .build();
    }

    /**
     * Get all active broadcasts
     */
    public List<BroadcastResponse> getActiveBroadcasts() {
        List<BroadcastMessage> broadcasts = broadcastRepository.findActiveBroadcasts();
        return broadcasts.stream()
                .map(broadcast -> getBroadcast(broadcast.getId())) // broadcast.getId() is already Long
                .collect(Collectors.toList());
    }

    /**
     * Cancel a broadcast
     */
    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + id));
        
        broadcastRepository.updateStatus(id, "CANCELLED");
        
        // Send cancellation event
        List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(id);
        List<String> targetUsers = userBroadcasts.stream()
                .map(UserBroadcastMessage::getUserId)
                .distinct()
                .collect(Collectors.toList());
        
        sendBroadcastEvent(broadcast, targetUsers, "CANCELLED");
        
        log.info("Broadcast cancelled: {}", id);
    }

     /**
     * Get user messages
     */
    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);
        
        List<UserBroadcastMessage> userMessages = userBroadcastRepository.findByUserId(userId);
        
        return userMessages.stream()
                .map(this::buildUserBroadcastResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get unread messages for user
     */
    public List<UserBroadcastResponse> getUnreadMessages(String userId) {
        log.info("Getting unread messages for user: {}", userId);
        
        List<UserBroadcastMessage> unreadMessages = userBroadcastRepository.findUnreadByUserId(userId);
        
        return unreadMessages.stream()
                .map(this::buildUserBroadcastResponse)
                .collect(Collectors.toList());
    }

    /**
     * Mark message as read
     */
    @Transactional
    public void markMessageAsRead(String userId, Long messageId) {
        log.info("Marking message as read: user={}, message={}", userId, messageId);
        
        UserBroadcastMessage userMessage = userBroadcastRepository.findById(messageId)
                .orElseThrow(() -> new RuntimeException("User message not found: " + messageId));
        
        // Verify the message belongs to the user
        if (!userId.equals(userMessage.getUserId())) {
            throw new RuntimeException("Message does not belong to user: " + userId);
        }
        
        // Update read status and timestamp
        userBroadcastRepository.markAsRead(messageId, ZonedDateTime.now(ZoneOffset.UTC).toInstant().atZone(ZoneOffset.UTC));
        
        log.info("Message marked as read: user={}, message={}", userId, messageId);
    }

    /**
     * Build user broadcast response
     */
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
                .build();
    }
}