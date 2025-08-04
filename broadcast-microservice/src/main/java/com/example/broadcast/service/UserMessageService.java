package com.example.broadcast.service;

import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.exception.ResourceNotFoundException;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMessageService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final MessageStatusService messageStatusService;

    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);
        return userBroadcastRepository.findUserMessagesByUserId(userId);
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
            // Although this check is good, throwing ResourceNotFound might be misleading.
            // An alternative would be an AccessDeniedException or similar. For now, this is fine.
            throw new ResourceNotFoundException("Message does not belong to user: " + userId);
        }

        // Use an atomic query to update the status from UNREAD to READ.
        // This returns the number of rows affected (0 if it was already READ, 1 if the update succeeded).
        int updatedRows = userBroadcastRepository.markAsRead(messageId, ZonedDateTime.now(ZoneOffset.UTC));

        // Only if the status was successfully changed, increment stats and publish the event.
        if (updatedRows > 0) {
            broadcastStatisticsRepository.incrementReadCount(userMessage.getBroadcastId());
            // This is the crucial step: publish the event within the same transaction.
            messageStatusService.publishReadEvent(userMessage.getBroadcastId(), userId);
            log.info("Successfully marked message {} as read for user {} and published READ event.", messageId, userId);
        } else {
            log.warn("Message {} was already read for user {}. No action taken.", messageId, userId);
        }
    }
}