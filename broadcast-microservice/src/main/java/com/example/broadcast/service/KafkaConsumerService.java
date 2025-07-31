package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final CaffeineCacheService caffeineCacheService;
    
    @Getter
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name:broadcast-events}",
            groupId = "${spring.kafka.consumer.group-id:broadcast-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}", 
                event.getEventId(), topic, partition, offset);
        
        CompletableFuture.runAsync(() -> {
            try {
                handleEvent(event);
                acknowledgment.acknowledge();
                log.debug("Event processed and acknowledged: {}", event.getEventId());
            } catch (Exception e) {
                log.error("Error processing event {}: {}", event.getEventId(), e.getMessage());
                throw new RuntimeException("Event processing failed", e);
            }
        }, executorService);
    }

    private void handleEvent(MessageDeliveryEvent event) {
        switch (event.getEventType()) {
            case "CREATED":
                handleBroadcastCreated(event);
                break;
            case "READ":
                handleMessageRead(event);
                break;
            case "CANCELLED":
                handleBroadcastCancelled(event);
                break;
            case "EXPIRED":
                handleBroadcastExpired(event);
                break;
            default:
                log.warn("Unknown event type: {}", event.getEventType());
        }
    }

    private void handleBroadcastCreated(MessageDeliveryEvent event) {
        log.info("Handling broadcast created event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        
        try {
            boolean isOnline = caffeineCacheService.isUserOnline(event.getUserId()) || 
                              sseService.isUserConnected(event.getUserId());
            
            if (isOnline) {
                // Delegate delivery and status updates entirely to the SseService
                sseService.handleMessageEvent(event);
                log.info("Broadcast event for online user {} forwarded to SSE service.", event.getUserId());
            } else {
                log.info("User {} is offline, message remains pending", event.getUserId());
                caffeineCacheService.cachePendingEvent(event);
            }
        } catch (Exception e) {
            log.error("Error handling broadcast created event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle broadcast created event", e);
        }
    }

    private void handleMessageRead(MessageDeliveryEvent event) {
        log.info("Handling message read event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        try {
            // Forward to SSE service to notify other potential client sessions
            sseService.handleMessageEvent(event);
            // Update cache if necessary
            caffeineCacheService.updateMessageReadStatus(event.getUserId(), event.getBroadcastId());
        } catch (Exception e) {
            log.error("Error handling message read event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle message read event", e);
        }
    }

    private void handleBroadcastCancelled(MessageDeliveryEvent event) {
        log.info("Handling broadcast cancelled event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        try {
            // Remove from pending cache if it exists
            caffeineCacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
            // Notify connected clients
            sseService.handleMessageEvent(event);
        } catch (Exception e) {
            log.error("Error handling broadcast cancelled event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle broadcast cancelled event", e);
        }
    }

    private void handleBroadcastExpired(MessageDeliveryEvent event) {
        log.info("Handling broadcast expired event for user: {}, broadcast: {}",
                event.getUserId(), event.getBroadcastId());
        try {
            sseService.handleMessageEvent(event);
        } catch (Exception e) {
            log.error("Error handling broadcast expired event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle broadcast expired event", e);
        }
    }
}
