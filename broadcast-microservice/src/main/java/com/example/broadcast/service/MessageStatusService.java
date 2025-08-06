package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageStatusService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    // --- REFACTORED DEPENDENCY ---
    private final OutboxEventPublisher outboxEventPublisher;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetMessageForRedrive(Long userBroadcastMessageId) {
        userBroadcastRepository.updateDeliveryStatus(userBroadcastMessageId, Constants.DeliveryStatus.PENDING.name());
        log.info("Reset UserBroadcastMessage (ID: {}) to PENDING for redrive in a new transaction.", userBroadcastMessageId);
    }

    @Transactional
    public void updateMessageToDelivered(Long userBroadcastMessageId, Long broadcastId) {
        int updatedRows = userBroadcastRepository.updateDeliveryStatus(userBroadcastMessageId, Constants.DeliveryStatus.DELIVERED.name());
        if (updatedRows > 0) {
            broadcastStatisticsRepository.incrementDeliveredCount(broadcastId);
            log.info("Updated message {} to DELIVERED and incremented stats for broadcast {}.", userBroadcastMessageId, broadcastId);
        }
    }

    @Transactional
    public void publishReadEvent(Long broadcastId, String userId, String topicName) {
        MessageDeliveryEvent eventPayload = MessageDeliveryEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .broadcastId(broadcastId)
            .userId(userId)
            .eventType(Constants.EventType.READ.name())
            .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
            .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
            .message("User marked message as read")
            .build();
        
        // REFACTORED: Use the dedicated publisher service
        outboxEventPublisher.publish(eventPayload, topicName);
    }
}