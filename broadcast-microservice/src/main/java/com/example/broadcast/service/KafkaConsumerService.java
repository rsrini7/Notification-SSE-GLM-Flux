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
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

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
    @Transactional
    public void processBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}",
                event.getEventId(), topic, partition, offset);
        
        handleEvent(event);

        acknowledgment.acknowledge();
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
        log.info("Handling broadcast created event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        try {
            // --- START: Definitive Fix for Race Condition ---
            // This retry loop handles the eventual consistency delay between the producer's
            // transaction commit and the data becoming visible to the consumer's transaction.
            BroadcastMessage broadcastMessage = null;
            int maxRetries = 4;
            long delayMs = 250;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                Optional<BroadcastMessage> optionalMessage = broadcastRepository.findById(event.getBroadcastId());
                if (optionalMessage.isPresent()) {
                    broadcastMessage = optionalMessage.get();
                    if (attempt > 1) {
                        log.info("Successfully found broadcast message ID: {} on attempt {}/{}", event.getBroadcastId(), attempt, maxRetries);
                    }
                    break;
                } else {
                    log.warn("Attempt {}/{} failed to find broadcast message ID: {}. Retrying in {}ms...", attempt, maxRetries, event.getBroadcastId(), delayMs);
                    if (attempt < maxRetries) {
                        try {
                            Thread.sleep(delayMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Consumer thread interrupted during retry delay", e);
                        }
                    }
                }
            }

            if (broadcastMessage == null) {
                // If the message is still not found after all retries, it's a genuine issue.
                throw new IllegalStateException("Broadcast message not found for ID: " + event.getBroadcastId() + " after " + maxRetries + " retries.");
            }
            // --- END: Definitive Fix for Race Condition ---

            // The "FAIL_ME" test logic remains the same.
            if (broadcastMessage.getContent() != null && broadcastMessage.getContent().contains("FAIL_ME")) {
                log.warn("Poison pill 'FAIL_ME' detected in broadcast message content. Simulating processing failure.");
                throw new RuntimeException("Simulating a poison pill message failure for DLT testing.");
            }

            // The rest of the business logic remains the same.
            boolean isOnline = caffeineCacheService.isUserOnline(event.getUserId()) || sseService.isUserConnected(event.getUserId());
            if (isOnline) {
                sseService.handleMessageEvent(event);
                log.info("Broadcast event for online user {} forwarded to SSE service.", event.getUserId());
            } else {
                log.info("User {} is offline, message remains pending", event.getUserId());
                caffeineCacheService.cachePendingEvent(event);
            }
        } catch (Exception e) {
            log.error("An unexpected error occurred while processing event {}: {}", event.getEventId(), e.getMessage());
            throw new RuntimeException("Failed to handle broadcast created event", e);
        }
    }

    private void handleMessageRead(MessageDeliveryEvent event) {
        log.info("Handling message read event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
        caffeineCacheService.updateMessageReadStatus(event.getUserId(), event.getBroadcastId());
    }

    private void handleBroadcastCancelled(MessageDeliveryEvent event) {
        log.info("Handling broadcast cancelled event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        caffeineCacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }

    private void handleBroadcastExpired(MessageDeliveryEvent event) {
        log.info("Handling broadcast expired event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }
}