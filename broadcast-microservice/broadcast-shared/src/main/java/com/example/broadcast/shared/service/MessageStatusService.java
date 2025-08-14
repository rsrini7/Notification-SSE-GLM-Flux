package com.example.broadcast.shared.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;


import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
     * Resets a message's status to PENDING in a new, independent transaction.
     * This is critical for the DLT redrive process to ensure the state is committed
     * before the message is re-queued in Kafka.
     * @param userBroadcastMessageId The ID of the message to reset.
     */
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

     /**
     * Updates the status of a specific UserBroadcastMessage to DELIVERED.
     * This method is now primarily for the "fan-out-on-write" (SELECTED user) path.
     *
     * @return true if the status was successfully updated from PENDING to DELIVERED.
     */
    @Transactional
    public boolean updateMessageStatusToDelivered(String userId, Long broadcastId) {
        Optional<UserBroadcastMessage> userMessageOpt = userBroadcastRepository.findByUserIdAndBroadcastId(userId, broadcastId);

        if (userMessageOpt.isPresent()) {
            UserBroadcastMessage userMessage = userMessageOpt.get();
            if (Constants.DeliveryStatus.PENDING.name().equals(userMessage.getDeliveryStatus())) {
                userMessage.setDeliveryStatus(Constants.DeliveryStatus.DELIVERED.name());
                userMessage.setDeliveredAt(ZonedDateTime.now(ZoneOffset.UTC));
                userBroadcastRepository.update(userMessage);
                broadcastStatisticsRepository.incrementDeliveredCount(broadcastId);
                return true;
            }
        }
        return false;
    }

    /**
     * Publishes a READ event to the outbox.
     * It now checks if a UserBroadcastMessage record exists before publishing.
     */
    @Transactional
    public void publishReadEvent(Long broadcastId, String userId) {
        Optional<UserBroadcastMessage> userMessageOpt = userBroadcastRepository.findByUserIdAndBroadcastId(userId, broadcastId);
        
        if (userMessageOpt.isEmpty()) {
            log.info("Skipping READ event publication for user {} and broadcast {}. No user-specific record found (likely a group broadcast).", userId, broadcastId);
            return;
        }

        MessageDeliveryEvent event = MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcastId)
                .userId(userId)
                .eventType(Constants.EventType.READ.name())
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .build();

        String topicName = appProperties.getKafka().getTopic().getNameActions();
        outboxEventPublisher.publish(event, userId, Constants.EventType.READ.name(), topicName);
        log.info("Published READ event for user: {}, broadcast: {}", userId, broadcastId);
    }

}