package com.example.broadcast.user.service;

import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.shared.dto.cache.UserMessageInfo;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.cache.CacheService;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap; // Import for locking
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMessageService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService;
    private final AppProperties appProperties;

    // START OF CHANGE: Add a map to hold locks for cache rebuilding
    private final ConcurrentHashMap<Long, Object> broadcastContentLocks = new ConcurrentHashMap<>();
    // END OF CHANGE

    @Transactional(readOnly = true)
    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);

        List<UserMessageInfo> cachedMessages = cacheService.getCachedUserMessages(userId);
        if (cachedMessages != null && !cachedMessages.isEmpty()) {
            log.info("Cache HIT for user messages: {}", userId);
            return cachedMessages.stream()
                .map(this::enrichUserMessageInfo)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
        }

        log.info("Cache MISS for user messages: {}. Fetching from database.", userId);
        List<UserBroadcastResponse> dbMessages = userBroadcastRepository.findUserMessagesByUserId(userId);

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
        return userBroadcastRepository.findUserMessagesByUserId(userId);
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

    private UserMessageInfo toUserMessageInfo(UserBroadcastResponse response) {
        return new UserMessageInfo(
            response.getId(),
            response.getBroadcastId(),
            response.getDeliveryStatus(),
            response.getReadStatus(),
            response.getCreatedAt()
        );
    }

    // START OF CHANGES: Implemented double-checked locking to prevent cache stampede
    private Optional<UserBroadcastResponse> enrichUserMessageInfo(UserMessageInfo info) {
        Optional<BroadcastMessage> broadcastOpt = cacheService.getBroadcastContent(info.getBroadcastId());

        if (broadcastOpt.isEmpty()) {
            // Get a specific lock object for the broadcastId
            Object lock = broadcastContentLocks.computeIfAbsent(info.getBroadcastId(), k -> new Object());
            
            synchronized (lock) {
                // Double-check: Another thread might have rebuilt the cache while we were waiting for the lock.
                broadcastOpt = cacheService.getBroadcastContent(info.getBroadcastId());
                if (broadcastOpt.isPresent()) {
                    log.info("Broadcast content for ID {} was populated by another thread. Using cached value.", info.getBroadcastId());
                } else {
                    // This is the only thread that will hit the DB for this key.
                    log.warn("Broadcast content for ID {} was not in cache. Fetching from DB.", info.getBroadcastId());
                    broadcastOpt = broadcastRepository.findById(info.getBroadcastId());
                    broadcastOpt.ifPresent(cacheService::cacheBroadcastContent);
                }
            }
            // Remove the lock object once we're done to prevent the map from growing indefinitely
            broadcastContentLocks.remove(info.getBroadcastId());
        }

        if (broadcastOpt.isEmpty()) {
            log.error("Data integrity issue: Could not find broadcast content for ID {} in cache or DB.", info.getBroadcastId());
            return Optional.empty();
        }

        BroadcastMessage broadcast = broadcastOpt.get();
        
        return Optional.of(UserBroadcastResponse.builder()
            .id(info.getMessageId())
            .broadcastId(info.getBroadcastId())
            .userId(broadcast.getSenderId()) 
            .deliveryStatus(info.getDeliveryStatus())
            .readStatus(info.getReadStatus())
            .createdAt(info.getCreatedAt())
            .senderName(broadcast.getSenderName())
            .content(broadcast.getContent())
            .priority(broadcast.getPriority())
            .category(broadcast.getCategory())
            .broadcastCreatedAt(broadcast.getCreatedAt())
            .expiresAt(broadcast.getExpiresAt())
            .build());
    }
}