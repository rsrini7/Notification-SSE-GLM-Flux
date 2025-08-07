package com.example.broadcast.user.service;

import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.shared.dto.cache.UserMessageInfo; // Import UserMessageInfo
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.cache.CacheService; // Import CacheService
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.broadcast.shared.service.MessageStatusService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.broadcast.shared.config.AppProperties;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors; // Import Collectors

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMessageService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService; // Inject CacheService
    private final AppProperties appProperties;

    // Implemented cache-aside pattern
    @Transactional(readOnly = true)
    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);

        // 1. Try to fetch from cache first
        List<UserMessageInfo> cachedMessages = cacheService.getCachedUserMessages(userId);
        if (cachedMessages != null && !cachedMessages.isEmpty()) {
            log.info("Cache HIT for user messages: {}", userId);
            // We need to enrich the cached info with broadcast content
            return cachedMessages.stream()
                .map(this::enrichUserMessageInfo)
                .collect(Collectors.toList());
        }

        // 2. If cache miss, fetch from the database
        log.info("Cache MISS for user messages: {}. Fetching from database.", userId);
        List<UserBroadcastResponse> dbMessages = userBroadcastRepository.findUserMessagesByUserId(userId);

        // 3. Populate the cache for the next request
        if (!dbMessages.isEmpty()) {
            List<UserMessageInfo> messagesToCache = dbMessages.stream()
                .map(this::toUserMessageInfo)
                .collect(Collectors.toList());
            cacheService.cacheUserMessages(userId, messagesToCache);
        }

        return dbMessages;
    }

    public List<UserBroadcastResponse> getUnreadMessages(String userId) {
        log.info("Getting unread messages for user: {}", userId);
        return userBroadcastRepository.findUnreadMessagesByUserId(userId);
    }

    @Transactional
    public void markMessageAsRead(String userId, Long messageId) {
        log.info("Attempting to mark message as read: user={}, message={}", userId, messageId);
        UserBroadcastMessage userMessage = userBroadcastRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("User message not found with ID: " + messageId));
        if (!userId.equals(userMessage.getUserId())) {
            throw new ResourceNotFoundException("Message does not belong to user: " + userId);
        }

        int updatedRows = userBroadcastRepository.markAsRead(messageId, ZonedDateTime.now(ZoneOffset.UTC));
        if (updatedRows > 0) {
            broadcastStatisticsRepository.incrementReadCount(userMessage.getBroadcastId());
            // Invalidate or update the cache for this user
            cacheService.updateMessageReadStatus(userId, userMessage.getBroadcastId());
            
            BroadcastMessage parentBroadcast = broadcastRepository.findById(userMessage.getBroadcastId())
                .orElseThrow(() -> new IllegalStateException("Cannot publish READ event. Original broadcast (ID: " + userMessage.getBroadcastId() + ") not found."));
            
            String topicName = Constants.TargetType.ALL.name().equals(parentBroadcast.getTargetType()) 
                ? appProperties.getKafka().getTopic().getNameAll() 
                : appProperties.getKafka().getTopic().getNameSelected();
            
            messageStatusService.publishReadEvent(userMessage.getBroadcastId(), userId, topicName);
            log.info("Successfully marked message {} as read for user {} and published READ event to topic {}.", messageId, userId, topicName);
        } else {
            log.warn("Message {} was already read for user {}. No action taken.", messageId, userId);
        }
    }

    /**
     * Converts a full UserBroadcastResponse from the database into a lean UserMessageInfo for caching.
     */
    private UserMessageInfo toUserMessageInfo(UserBroadcastResponse response) {
        return new UserMessageInfo(
            response.getId(),
            response.getBroadcastId(),
            response.getDeliveryStatus(),
            response.getReadStatus(),
            response.getCreatedAt()
        );
    }

    /**
     * Enriches a cached UserMessageInfo object with full content from the broadcastContentCache.
     */
    private UserBroadcastResponse enrichUserMessageInfo(UserMessageInfo info) {
        // Fetch the broadcast content from the cache (implemented previously)
        BroadcastMessage broadcast = cacheService.getBroadcastContent(info.getBroadcastId())
            .orElse(new BroadcastMessage()); // Or handle as an error if preferred

        // Build the full response DTO for the UI
        return UserBroadcastResponse.builder()
            .id(info.getMessageId())
            .broadcastId(info.getBroadcastId())
            .userId(broadcast.getSenderId()) // Assuming senderId can represent the user for this context
            .deliveryStatus(info.getDeliveryStatus())
            .readStatus(info.getReadStatus())
            .createdAt(info.getCreatedAt())
            .senderName(broadcast.getSenderName())
            .content(broadcast.getContent())
            .priority(broadcast.getPriority())
            .category(broadcast.getCategory())
            .broadcastCreatedAt(broadcast.getCreatedAt())
            .expiresAt(broadcast.getExpiresAt())
            .build();
    }

}