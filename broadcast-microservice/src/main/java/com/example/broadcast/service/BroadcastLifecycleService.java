package com.example.broadcast.service;

import com.example.broadcast.config.AppProperties;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.exception.ResourceNotFoundException;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Manages the state transitions (lifecycle) of a broadcast, such as
 * cancellation and expiration.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastLifecycleService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final AppProperties appProperties;

    /**
     * Cancels a broadcast, updating its status and notifying users via an event.
     *
     * @param id The ID of the broadcast to cancel.
     */
    @Transactional
    public void cancelBroadcast(Long id) {
        BroadcastMessage broadcast = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));

        broadcast.setStatus(Constants.BroadcastStatus.CANCELLED.name());
        broadcastRepository.update(broadcast);

        int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(id, Constants.DeliveryStatus.SUPERSEDED.name());
        log.info("Updated {} pending user messages to SUPERSEDED for cancelled broadcast ID: {}", updatedCount, id);

        String topicName = Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) ? appProperties.getKafka().getTopic().getNameAll() : appProperties.getKafka().getTopic().getNameSelected();
        List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(id);
        for (UserBroadcastMessage userMessage : userBroadcasts) {
            MessageDeliveryEvent eventPayload = createLifecycleEvent(broadcast, userMessage.getUserId(), Constants.EventType.CANCELLED, "Broadcast CANCELLED");
            outboxEventPublisher.publish(eventPayload, topicName);
        }
        log.info("Broadcast cancelled: {}. Published cancellation events to outbox.", id);
    }

    /**
     * Expires a broadcast, updating its status and notifying users.
     *
     * @param broadcastId The ID of the broadcast to expire.
     */
    @Transactional
    public void expireBroadcast(Long broadcastId) {
        BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + broadcastId));

        if (Constants.BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) { 
            broadcast.setStatus(Constants.BroadcastStatus.EXPIRED.name()); 
            broadcastRepository.update(broadcast);
            
            int updatedCount = userBroadcastRepository.updatePendingStatusesByBroadcastId(broadcastId, Constants.DeliveryStatus.SUPERSEDED.name()); 
            log.info("Updated {} pending user messages to SUPERSEDED for expired broadcast ID: {}", updatedCount, broadcastId); 

            String topicName = Constants.TargetType.ALL.name().equals(broadcast.getTargetType()) ? appProperties.getKafka().getTopic().getNameAll() : appProperties.getKafka().getTopic().getNameSelected();
            List<UserBroadcastMessage> userBroadcasts = userBroadcastRepository.findByBroadcastId(broadcastId);
            for (UserBroadcastMessage userMessage : userBroadcasts) {
                MessageDeliveryEvent eventPayload = createLifecycleEvent(broadcast, userMessage.getUserId(), Constants.EventType.EXPIRED, "Broadcast EXPIRED");
                outboxEventPublisher.publish(eventPayload, topicName);
            }
            log.info("Broadcast expired: {}. Published expiration events to outbox.", broadcastId);
        }
    }

    private MessageDeliveryEvent createLifecycleEvent(BroadcastMessage broadcast, String userId, Constants.EventType eventType, String message) {
        return MessageDeliveryEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .broadcastId(broadcast.getId())
                .userId(userId)
                .eventType(eventType.name())
                .podId(System.getenv().getOrDefault("POD_NAME", "pod-local"))
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC))
                .message(message)
                .build();
    }
}