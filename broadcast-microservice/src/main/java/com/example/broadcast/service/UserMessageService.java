package com.example.broadcast.service;

import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.exception.ResourceNotFoundException;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.broadcast.config.AppProperties;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMessageService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final MessageStatusService messageStatusService;
    
    private final AppProperties appProperties;

    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);
        return userBroadcastRepository.findUserMessagesByUserId(userId);
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

            // Fetch the parent broadcast to determine the correct topic
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
}