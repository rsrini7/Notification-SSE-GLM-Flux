// Location: src/main/java/com/example/broadcast/shared/service/MessageStatusService.java
package com.example.broadcast.shared.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.MessageRedriveRequestedEvent;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
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
    private final OutboxEventPublisher outboxEventPublisher;

    /**
     * NEW: Kafka listener that consumes redrive request events.
     * This decouples the DLT redrive process from the status update logic.
     */
    @KafkaListener(
            topics = "${broadcast.kafka.topic.name.commands:broadcast-commands}",
            groupId = "${broadcast.kafka.consumer.commands-group-id:broadcast-commands-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void listenForRedriveCommands(MessageRedriveRequestedEvent event, Acknowledgment acknowledgment) {
        log.info("Consumed MessageRedriveRequestedEvent for UserBroadcastMessage ID: {}", event.getUserBroadcastMessageId());
        try {
            resetMessageForRedrive(event.getUserBroadcastMessageId());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            // This is a critical failure, if we can't update the DB, we shouldn't ack the command.
            // It will be retried and eventually go to the DLT for the commands topic if it keeps failing.
            log.error("Failed to process redrive command for message ID: {}", event.getUserBroadcastMessageId(), e);
            throw new RuntimeException("Failed to reset message status for redrive", e);
        }
    }

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
        
        // --- CORRECTED CALL to generic publisher ---
        outboxEventPublisher.publish(
            eventPayload,
            userId,                       // aggregateId
            eventPayload.getEventType(),  // eventType
            topicName
        );
    }
}