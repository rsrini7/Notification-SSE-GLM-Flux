package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final CaffeineCacheService caffeineCacheService;
    private final BroadcastRepository broadcastRepository;

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name:broadcast-events}",
            groupId = "${spring.kafka.consumer.group-id:broadcast-service-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )

    // --- START OF SIMPLIFICATION ---
    // The Acknowledgment is now handled automatically by the container and error handler.
    // We can remove the try/catch block and let exceptions propagate naturally.
    public void processBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}",
                event.getEventId(), topic, partition, offset);
        
        // Directly call the handler. If this throws an exception, the DefaultErrorHandler will catch it.
        handleEvent(event);

        // Acknowledge the message only if processing succeeds.
        acknowledgment.acknowledge();
    }
    // --- END OF SIMPLIFICATION ---

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

    // The logic inside this method is now correct, but we'll re-add the re-throwing
    // of the final exception to ensure it propagates cleanly.
    private void handleBroadcastCreated(MessageDeliveryEvent event) {
        log.info("Handling broadcast created event for user: {}, broadcast: {}",
                event.getUserId(), event.getBroadcastId());
        try {
            BroadcastMessage broadcastMessage = broadcastRepository.findById(event.getBroadcastId())
                    .orElseThrow(() -> new IllegalStateException("Broadcast message not found for ID: " + event.getBroadcastId()));

            if (broadcastMessage.getContent() != null && broadcastMessage.getContent().contains("FAIL_ME")) {
                log.warn("Poison pill 'FAIL_ME' detected in broadcast message content. Simulating processing failure.");
                throw new RuntimeException("Simulating a poison pill message failure for DLT testing.");
            }

            boolean isOnline = caffeineCacheService.isUserOnline(event.getUserId()) ||
                              sseService.isUserConnected(event.getUserId());
            if (isOnline) {
                sseService.handleMessageEvent(event);
                log.info("Broadcast event for online user {} forwarded to SSE service.", event.getUserId());
            } else {
                log.info("User {} is offline, message remains pending", event.getUserId());
                caffeineCacheService.cachePendingEvent(event);
            }
        }
        catch (DataAccessException dae) {
            // Granular handling for potentially recoverable database errors.
            log.warn("A recoverable data access error occurred while processing event {}: {}. Will allow container error handler to retry.", event.getEventId(), dae.getMessage());
            // We rethrow to ensure the Mono's doOnError captures it, triggering the DefaultErrorHandler
            throw new RuntimeException("Failed to handle broadcast created event due to a database issue.", dae);
        }        
        catch (Exception e) {
            log.error("An unexpected error occurred while processing event {}: {}", event.getEventId(), e.getMessage());
            // Re-throw the original exception to be handled by the listener container
            throw new RuntimeException("Failed to handle broadcast created event", e);
        }
    }

    private void handleMessageRead(MessageDeliveryEvent event) {
        log.info("Handling message read event for user: {}, broadcast: {}",
                event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
        caffeineCacheService.updateMessageReadStatus(event.getUserId(), event.getBroadcastId());
    }

    private void handleBroadcastCancelled(MessageDeliveryEvent event) {
        log.info("Handling broadcast cancelled event for user: {}, broadcast: {}",
                event.getUserId(), event.getBroadcastId());
        caffeineCacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }

    private void handleBroadcastExpired(MessageDeliveryEvent event) {
        log.info("Handling broadcast expired event for user: {}, broadcast: {}",
                event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }
}