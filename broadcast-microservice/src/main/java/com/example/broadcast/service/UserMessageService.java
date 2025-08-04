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
        log.info("Marking message as read: user={}, message={}", userId, messageId);
        UserBroadcastMessage userMessage = userBroadcastRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("User message not found with ID: " + messageId));

        if (!userId.equals(userMessage.getUserId())) {
            throw new ResourceNotFoundException("Message does not belong to user: " + userId);
        }

        // Only update if the status is currently UNREAD to avoid redundant DB writes and metric increments.
        if ("UNREAD".equals(userMessage.getReadStatus())) {
            userBroadcastRepository.markAsRead(messageId, ZonedDateTime.now(ZoneOffset.UTC));
            broadcastStatisticsRepository.incrementReadCount(userMessage.getBroadcastId());
        }
    }
}