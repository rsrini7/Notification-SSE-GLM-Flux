package com.example.broadcast.shared.service;

import com.example.broadcast.admin.service.BroadcastExpirationManager;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final BroadcastRepository broadcastRepository;
    private final CacheService cacheService;
    private final BroadcastExpirationManager broadcastExpirationManager; // Inject the new manager

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
            
            isFireAndForget(broadcastId).ifPresent(isFire -> {
                if (isFire) {
                    log.info("Broadcast {} is Fire-and-Forget. Triggering expiration via manager.", broadcastId);
                    broadcastExpirationManager.expireFireAndForgetBroadcast(broadcastId);
                }
            });
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
        outboxEventPublisher.publish(
            eventPayload,
            userId,
            eventPayload.getEventType(),
            topicName
        );
    }

    private Optional<Boolean> isFireAndForget(Long broadcastId) {
        Optional<BroadcastMessage> broadcastOpt = cacheService.getBroadcastContent(broadcastId);
        if (broadcastOpt.isEmpty()) {
            broadcastOpt = broadcastRepository.findById(broadcastId);
        }
        return broadcastOpt.map(BroadcastMessage::isFireAndForget);
    }
}