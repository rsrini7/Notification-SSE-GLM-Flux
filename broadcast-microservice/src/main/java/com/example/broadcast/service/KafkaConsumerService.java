package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.util.Constants.EventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final CacheService cacheService;
    private final ObjectMapper objectMapper;
    
    private static final Map<String, Integer> TRANSIENT_FAILURE_ATTEMPTS = new ConcurrentHashMap<>();
    private static final int MAX_AUTOMATIC_ATTEMPTS = 3; // 1 initial + 2 retries
    
    @KafkaListener(
            topics = "${broadcast.kafka.topic.name:broadcast-events}",
            groupId = "${spring.kafka.consumer.group-id:broadcast-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processBroadcastEvent(
            @Payload byte[] payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        try {
            MessageDeliveryEvent event = objectMapper.readValue(payload, MessageDeliveryEvent.class);
            log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}",
                    event.getEventId(), topic, partition, offset);

            if (event.isTransientFailure()) {
                int attempts = TRANSIENT_FAILURE_ATTEMPTS.getOrDefault(event.getEventId(), 0);
                
                // Fail for the initial attempt and all configured automatic retries.
                if (attempts < MAX_AUTOMATIC_ATTEMPTS) {
                    TRANSIENT_FAILURE_ATTEMPTS.put(event.getEventId(), attempts + 1);
                    log.warn("Transient failure flag detected for eventId: {}. Simulating failure, attempt {}/{}", 
                             event.getEventId(), attempts + 1, MAX_AUTOMATIC_ATTEMPTS);
                    throw new RuntimeException("Simulating a transient, recoverable error for DLT redrive testing.");
                } else {
                    // If we've already failed 3 times, this must be a manual redrive from the DLQ.
                    log.info("Successfully redriving eventId with transient failure flag: {}. Attempts ({}) exceeded max.", 
                             event.getEventId(), attempts);
                    // Clean up the map to allow this message to be tested again in the future.
                    TRANSIENT_FAILURE_ATTEMPTS.remove(event.getEventId());
                }
            }

            handleEvent(event);
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process message from topic {}. Root cause: {}", topic, e.getMessage());
            throw new RuntimeException("Failed to process message", e);
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
        } else {
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
        sseService.handleMessageEvent(event);
    }

    private void handleBroadcastExpired(MessageDeliveryEvent event) {
        log.info("Handling broadcast expired event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }
}