package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.exception.MessageProcessingException;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final CacheService cacheService;
    private final UserService userService;
    private final BroadcastRepository broadcastRepository;

    private static final Map<String, Integer> TRANSIENT_FAILURE_ATTEMPTS = new ConcurrentHashMap<>();
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3;

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name.selected:broadcast-events-selected}",
            groupId = "${spring.kafka.consumer.group-id:broadcast-service-group}-selected",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processSelectedUsersBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        processBroadcastEvent(event, topic, partition, offset, acknowledgment);
    }
    
    @KafkaListener(
        topics = "${broadcast.kafka.topic.name.group:broadcast-events-group}",
        groupId = "${spring.kafka.consumer.group-id:broadcast-service-group}-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void processGroupBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info("Received group broadcast event for broadcast ID: {}", event.getBroadcastId());
        
        try {
            BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId())
                .orElseThrow(() -> new IllegalStateException("Broadcast not found for group event: " + event.getBroadcastId()));

            List<String> targetUserIds;
            if ("ALL".equals(broadcast.getTargetType())) {
                targetUserIds = userService.getAllUserIds();
            } else { // ROLE
                targetUserIds = broadcast.getTargetIds().stream()
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
            }

            List<String> onlineUsers = cacheService.getOnlineUsers();
            List<String> usersToNotify = targetUserIds.stream()
                .filter(onlineUsers::contains)
                .collect(Collectors.toList());

            log.info("Delivering group broadcast {} to {} online users out of {} total targets.", event.getBroadcastId(), usersToNotify.size(), targetUserIds.size());
            
            for (String userId : usersToNotify) {
                MessageDeliveryEvent userEvent = event.toBuilder().userId(userId).build();
                handleEvent(userEvent);
            }

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process group message from topic {}. Root cause: {}", topic, e.getMessage());
            throw new MessageProcessingException("Failed to process group message", e, event);
        }
    }

    private void processBroadcastEvent(
            MessageDeliveryEvent event,
            String topic,
            String partition,
            long offset,
            Acknowledgment acknowledgment) {
        
        try {
            log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}",
                    event.getEventId(), topic, partition, offset);
            
            if (event.isTransientFailure()) {
                int attempts = TRANSIENT_FAILURE_ATTEMPTS.getOrDefault(event.getEventId(), 0);
                if (attempts < MAX_AUTOMATIC_ATTEMPTS) {
                    TRANSIENT_FAILURE_ATTEMPTS.put(event.getEventId(), attempts + 1);
                    log.warn("Transient failure flag detected for eventId: {}. Simulating failure BEFORE processing, attempt {}/{}", 
                             event.getEventId(), attempts + 1, MAX_AUTOMATIC_ATTEMPTS);
                    throw new RuntimeException("Simulating a transient, recoverable error for DLT redrive testing.");
                } else {
                    log.info("Successfully redriving eventId with transient failure flag: {}. Attempts ({}) exceeded max.", 
                             event.getEventId(), attempts);
                    TRANSIENT_FAILURE_ATTEMPTS.remove(event.getEventId());
                }
            }

            handleEvent(event);
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process message from topic {}. Root cause: {}", topic, e.getMessage());
            throw new MessageProcessingException("Failed to process message", e, event);
        }
    }

    private void handleEvent(MessageDeliveryEvent event) {
        EventType eventType;
        try {
            eventType = EventType.valueOf(event.getEventType());
        } catch (Exception e) {
            log.warn("Unknown or null event type: {}", event.getEventType());
            throw new IllegalArgumentException("Invalid event type received", e);
        }

        switch (eventType) {
            case CREATED:
                handleBroadcastCreated(event);
                break;
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
                log.warn("Unhandled event type: {}", event.getEventType());
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
        cacheService.updateMessageReadStatus(event.getUserId(), event.getBroadcastId());
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