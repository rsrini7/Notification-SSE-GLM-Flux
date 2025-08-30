package com.example.broadcast.shared.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageStatusService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final AppProperties appProperties;

    /**
     * Resets a message's status to PENDING in a new, independent transaction for DLT redrives.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetMessageForRedrive(Long userBroadcastMessageId) {
        userBroadcastRepository.updateDeliveryStatus(userBroadcastMessageId, Constants.DeliveryStatus.PENDING.name());
        log.info("Reset UserBroadcastMessage (ID: {}) to PENDING for redrive in a new transaction.", userBroadcastMessageId);
    }

    /**
     * Updates a message to DELIVERED and increments the central statistics counter.
     */
    @Transactional
    public void updateMessageToDelivered(Long userBroadcastMessageId, Long broadcastId) {
        int updatedRows = userBroadcastRepository.updateDeliveryStatus(userBroadcastMessageId, Constants.DeliveryStatus.DELIVERED.name());
        if (updatedRows > 0) {
            broadcastStatisticsRepository.incrementDeliveredCount(broadcastId);
            log.info("Updated message {} to DELIVERED and incremented stats for broadcast {}.", userBroadcastMessageId, broadcastId);
        }
    }

    /**
     * Publishes a READ event to the outbox. This now publishes to the central orchestration topic.
     */
    @Transactional
    public void publishReadEvent(Long broadcastId, String userId) {
        Optional<UserBroadcastMessage> userMessageOpt = userBroadcastRepository.findByUserIdAndBroadcastId(userId, broadcastId);
        
        if (userMessageOpt.isEmpty()) {
            log.info("No existing UserBroadcastMessage for user {}, broadcast {}. A new record was likely created.", userId, broadcastId);
        }

        MessageDeliveryEvent event = MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcastId)
                .userId(userId)
                .eventType(Constants.EventType.READ.name())
                .timestamp(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        // All user actions are now sent to the central orchestration topic for routing.
        String topicName = appProperties.getKafka().getTopic().getNameOrchestration();
        outboxEventPublisher.publish(event, userId, Constants.EventType.READ.name(), topicName);
        log.info("Published READ event for user: {}, broadcast: {} to orchestration topic.", userId, broadcastId);
    }
}