// broadcast-microservice/src/main/java/com/example/broadcast/service/KafkaConsumerService.java
package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


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
    public void processBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}",
                event.getEventId(), topic, partition, offset);

        // --- START OF CHANGES ---
        try {
            // By calling .block(), we ensure that the processing completes or fails
            // on the current thread. If handleEvent throws an exception, .block()
            // will re-throw it here, where the Kafka listener container can catch it.
            Mono.fromRunnable(() -> handleEvent(event))
                .subscribeOn(Schedulers.boundedElastic())
                .block(); // This is the key change.

            // If .block() completes without an exception, we can safely acknowledge.
            acknowledgment.acknowledge();
            log.debug("Event processed and acknowledged: {}", event.getEventId());

        } catch (Exception e) {
            // If .block() throws an exception, we log it and then re-throw it.
            // This allows the DefaultErrorHandler to take over and send the message to the DLT.
            log.error("Error processing event {}, propagating exception to Kafka listener container for DLT handling.", event.getEventId(), e);
            throw e;
        }
        // --- END OF CHANGES ---
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
            // First, fetch the full broadcast message from the database.
            BroadcastMessage broadcastMessage = broadcastRepository.findById(event.getBroadcastId())
                    .orElseThrow(() -> new IllegalStateException("Broadcast message not found for ID: " + event.getBroadcastId()));

            // Now, check the *actual content* of the message for the poison pill.
            if (broadcastMessage.getContent() != null && broadcastMessage.getContent().contains("FAIL_ME")) {
                log.warn("Poison pill 'FAIL_ME' detected in broadcast message content. Simulating processing failure.");
                throw new RuntimeException("Simulating a poison pill message failure for DLT testing.");
            }

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
        } catch (DataAccessException dae) {
            // Granular handling for potentially recoverable database errors.
            log.warn("A recoverable data access error occurred while processing event {}: {}. Will allow container error handler to retry.", event.getEventId(), dae.getMessage());
            // We rethrow to ensure the Mono's doOnError captures it, triggering the DefaultErrorHandler
            throw new RuntimeException("Failed to handle broadcast created event due to a database issue.", dae);
        } catch (Exception e) {
            // Granular handling for other, likely non-recoverable, errors.
            log.error("An unexpected error occurred while processing event {}: {}", event.getEventId(), e.getMessage());
            // We rethrow to ensure the Mono's doOnError captures it, triggering the DefaultErrorHandler
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