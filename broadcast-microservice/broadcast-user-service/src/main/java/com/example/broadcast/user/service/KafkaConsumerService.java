package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.exception.MessageProcessingException;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants.EventType;
import com.example.broadcast.shared.service.TestingConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    // Add this field to record the service's startup time
    private final ZonedDateTime startupTime = ZonedDateTime.now(ZoneOffset.UTC);

    private final SseService sseService;
    private final CacheService cacheService;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final TestingConfigurationService testingConfigurationService;

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name.selected:broadcast-events-selected}",
            groupId = "${broadcast.kafka.consumer.selected-group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processSelectedUsersBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        // If the event is older than the pod, ignore it.
        if (event.getTimestamp().isBefore(this.startupTime)) {
            log.trace("Skipping old message from pod restart. Event ID: {}", event.getEventId());
            acknowledgment.acknowledge(); // Acknowledge to advance offset
            return;
        }

        if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
            log.warn("DLT TEST MODE [SELECTED]: Simulating failure for broadcast ID: {}", event.getBroadcastId());
            throw new RuntimeException("Simulating DLT failure for broadcast ID: " + event.getBroadcastId());
        }

        try {
            handleBroadcastCreated(event);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process message for selected user. Root cause: {}", e.getMessage());
            throw new MessageProcessingException("Failed to process message", e, event);
        }
    }
    
    @KafkaListener(
        topics = "${broadcast.kafka.topic.name.user.group:broadcast-user-events-group}",
        groupId = "${broadcast.kafka.consumer.group-user-group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void processUserGroupBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        // If the event is older than the pod, ignore it.
        if (event.getTimestamp().isBefore(this.startupTime)) {
            log.trace("Skipping old message from pod restart. Event ID: {}", event.getEventId());
            acknowledgment.acknowledge(); // Acknowledge to advance offset
            return;
        }

        try {
            if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
                log.warn("DLT TEST MODE [GROUP]: Simulating failure for broadcast ID: {}", event.getBroadcastId());
                throw new RuntimeException("Simulating DLT failure for broadcast ID: " + event.getBroadcastId());
            }

            log.info("Received user broadcast event from group for broadcast ID: {} user ID: {}", event.getBroadcastId(), event.getUserId());
            
            handleBroadcastCreated(event);

            broadcastStatisticsRepository.incrementDeliveredCountBy(event.getBroadcastId(), 1);
            log.info("Updated delivery statistics for broadcast {}: incremented 1 delivered count for User: {}", event.getBroadcastId(), event.getUserId());

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process group message from topic {}. Root cause: {}", topic, e.getMessage(), e);
            throw new MessageProcessingException("Failed to process group message", e, event);
        }
    }

    /**
     * UPDATED: This listener is now the "worker" for user-specific actions.
     * It listens to the new topic with a DYNAMIC group ID to distribute the work.
     */
    @KafkaListener(
        topics = "${broadcast.kafka.topic.name.user.actions:broadcast-user-actions}",
        groupId = "${broadcast.kafka.consumer.actions-user-group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void processUserActionEvent(@Payload MessageDeliveryEvent event, Acknowledgment acknowledgment) {
        log.debug("Processing user-specific ACTION event: {} for user {} on broadcast: {}", event.getEventType(), event.getUserId(), event.getBroadcastId());

        // If the event is older than the pod, ignore it.
        if (event.getTimestamp().isBefore(this.startupTime)) {
            log.trace("Skipping old message from pod restart. Event ID: {}", event.getEventId());
            acknowledgment.acknowledge(); // Acknowledge to advance offset
            return;
        }

        try {
            EventType eventType = EventType.valueOf(event.getEventType());
            switch (eventType) {
                case READ:
                    handleMessageRead(event);
                    break;
                case CANCELLED:
                    handleBroadcastCancelled(event);
                    break;
                case EXPIRED:
                    handleBroadcastExpired(event);
                    break;
                default:
                    log.warn("Unhandled event type on user-actions topic: {}", event.getEventType());
            }
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process user action event. Root cause: {}", e.getMessage(), e);
            throw new MessageProcessingException("Failed to process user action event", e, event);
        }
    }

    private void handleBroadcastCreated(MessageDeliveryEvent event) {
        log.info("Handling broadcast created event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        boolean isOnline = cacheService.isUserOnline(event.getUserId()) || sseService.isUserConnected(event.getUserId());
        if (isOnline) {
            sseService.handleMessageEvent(event);
            log.info("Broadcast event for online user {} forwarded to SSE service.", event.getUserId());
        } else if (!event.isFireAndForget()) {
            log.info("User {} is offline, message remains pending", event.getUserId());
            cacheService.cachePendingEvent(event);
        }
    }
    
    private void handleMessageRead(MessageDeliveryEvent event) {
        log.info("Handling message read event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
        cacheService.removeMessageFromUserCache(event.getUserId(), event.getBroadcastId());
    }

    private void handleBroadcastCancelled(MessageDeliveryEvent event) {
        log.info("Handling broadcast cancelled event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        cacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
        cacheService.removeMessageFromUserCache(event.getUserId(), event.getBroadcastId());
        log.debug("Removed cancelled message from pending and active caches for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }

    private void handleBroadcastExpired(MessageDeliveryEvent event) {
        log.info("Handling broadcast expired event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        cacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
        cacheService.removeMessageFromUserCache(event.getUserId(), event.getBroadcastId());
        log.debug("Removed expired message from pending and active caches for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }
}