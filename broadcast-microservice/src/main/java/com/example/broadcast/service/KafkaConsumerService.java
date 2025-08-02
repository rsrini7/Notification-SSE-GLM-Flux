package com.example.broadcast.service;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.repository.BroadcastRepository;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final CaffeineCacheService caffeineCacheService;
    private final BroadcastRepository broadcastRepository;
    private final ObjectMapper objectMapper;

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
            handleEvent(event);

            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process message from topic {}. Root cause: {}", topic, e.getMessage());
            throw new RuntimeException("Failed to process message", e);
        }
    }

    private void handleEvent(MessageDeliveryEvent event) {
        // START OF REFACTORING: Use constants instead of hardcoded strings
        EventType eventType;
        try {
            eventType = EventType.valueOf(event.getEventType());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown event type: {}", event.getEventType());
            return;
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
        // END OF REFACTORING
    }

    private void handleBroadcastCreated(MessageDeliveryEvent event) {
        log.info("Handling broadcast created event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());

        // The "FAIL_ME" poison pill logic has been COMMENTED OUT to allow normal processing.                
        // BroadcastMessage broadcastMessage = broadcastRepository.findById(event.getBroadcastId())
        //         .orElseThrow(() -> new IllegalStateException("Broadcast message not found for ID: " + event.getBroadcastId()));
        // if (broadcastMessage.getContent() != null && broadcastMessage.getContent().contains("FAIL_ME")) {
        //     log.warn("Poison pill 'FAIL_ME' detected in broadcast message content. Simulating processing failure.");
        //     throw new RuntimeException("Simulating a poison pill message failure for DLT testing.");
        // }
        
        boolean isOnline = caffeineCacheService.isUserOnline(event.getUserId()) || sseService.isUserConnected(event.getUserId());
        if (isOnline) {
            sseService.handleMessageEvent(event);
            log.info("Broadcast event for online user {} forwarded to SSE service.", event.getUserId());
        } else {
            log.info("User {} is offline, message remains pending", event.getUserId());
            caffeineCacheService.cachePendingEvent(event);
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