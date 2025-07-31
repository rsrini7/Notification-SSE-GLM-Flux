package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.UserBroadcastRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final UserBroadcastRepository userBroadcastRepository;
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
            case "DELIVERED":
                handleMessageDelivered(event);
                break;
            case "READ":
                handleMessageRead(event);
                break;
            case "FAILED":
                handleDeliveryFailed(event);
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

    /**
     * **NEW:** Handles the broadcast expired event.
     */
    private void handleBroadcastExpired(MessageDeliveryEvent event) {
        log.info("Handling broadcast expired event for user: {}, broadcast: {}",
                event.getUserId(), event.getBroadcastId());
        try {
            // Notify user via SSE to remove the message from their UI
            sseService.handleMessageEvent(event);
        } catch (Exception e) {
            log.error("Error handling broadcast expired event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle broadcast expired event", e);
        }
    }

    private void handleBroadcastCreated(MessageDeliveryEvent event) {
        log.info("Handling broadcast created event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        
        try {
            boolean isOnline = caffeineCacheService.isUserOnline(event.getUserId()) || 
                              sseService.isUserConnected(event.getUserId());
            
            if (isOnline) {
                sseService.handleMessageEvent(event);
                updateDeliveryStatus(event.getBroadcastId(), event.getUserId(), "DELIVERED");
                log.info("Broadcast delivered to online user: {}", event.getUserId());
            } else {
                log.info("User {} is offline, message remains pending", event.getUserId());
                caffeineCacheService.cachePendingEvent(event);
            }
        } catch (Exception e) {
            log.error("Error handling broadcast created event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle broadcast created event", e);
        }
    }

    private void handleMessageDelivered(MessageDeliveryEvent event) {
        log.debug("Handling message delivered event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        
        try {
            // Update delivery status in database
            updateDeliveryStatus(event.getBroadcastId(), event.getUserId(), "DELIVERED");
            
            // Remove from pending cache
            caffeineCacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
            
            // Send delivery confirmation via SSE if user is connected
            sseService.handleMessageEvent(event);
            
        } catch (Exception e) {
            log.error("Error handling message delivered event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle message delivered event", e);
        }
    }

    private void handleMessageRead(MessageDeliveryEvent event) {
        log.info("Handling message read event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        
        try {
            // Update read status in database
            updateReadStatus(event.getBroadcastId(), event.getUserId(), "READ");
            
            // Send read confirmation via SSE
            sseService.handleMessageEvent(event);
            
            // Update cache
            caffeineCacheService.updateMessageReadStatus(event.getUserId(), event.getBroadcastId());
            
        } catch (Exception e) {
            log.error("Error handling message read event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle message read event", e);
        }
    }

    private void handleDeliveryFailed(MessageDeliveryEvent event) {
        log.warn("Handling delivery failed event for user: {}, broadcast: {}, error: {}", 
                event.getUserId(), event.getBroadcastId(), event.getErrorDetails());
        
        try {
            // Update delivery status in database
            updateDeliveryStatus(event.getBroadcastId(), event.getUserId(), "FAILED");
            
            // Remove from pending cache
            caffeineCacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
            
            // Log the failure for monitoring
            log.error("Message delivery failed - User: {}, Broadcast: {}, Error: {}", 
                    event.getUserId(), event.getBroadcastId(), event.getErrorDetails());
            
        } catch (Exception e) {
            log.error("Error handling delivery failed event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle delivery failed event", e);
        }
    }

    private void handleBroadcastCancelled(MessageDeliveryEvent event) {
        log.info("Handling broadcast cancelled event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        
        try {
            // Find and cancel pending messages for this user
            List<UserBroadcastMessage> pendingMessages = userBroadcastRepository.findByUserIdAndStatus(
                    event.getUserId(), "PENDING", "UNREAD");
            
            for (UserBroadcastMessage message : pendingMessages) {
                if (message.getBroadcastId().equals(event.getBroadcastId())) {
                    // Mark as failed (cancelled)
                    userBroadcastRepository.updateDeliveryStatus(message.getId(), "FAILED");
                    
                    // Remove from cache
                    caffeineCacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
                    
                    // Notify user via SSE
                    sseService.handleMessageEvent(event);
                }
            }
            
        } catch (Exception e) {
            log.error("Error handling broadcast cancelled event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle broadcast cancelled event", e);
        }
    }

    private void updateDeliveryStatus(Long broadcastId, String userId, String status) {
        try {
            List<UserBroadcastMessage> messages = userBroadcastRepository.findByUserIdAndStatus(
                    userId, "PENDING", "UNREAD");
            
            for (UserBroadcastMessage message : messages) {
                if (message.getBroadcastId().equals(broadcastId)) {
                    userBroadcastRepository.updateDeliveryStatus(message.getId(), status);
                    break;
                }
            }
            
        } catch (Exception e) {
            log.error("Error updating delivery status: {}", e.getMessage());
            throw new RuntimeException("Failed to update delivery status", e);
        }
    }

    private void updateReadStatus(Long broadcastId, String userId, String status) {
        try {
            List<UserBroadcastMessage> messages = userBroadcastRepository.findByUserIdAndStatus(
                    userId, "DELIVERED", "UNREAD");
            
            for (UserBroadcastMessage message : messages) {
                if (message.getBroadcastId().equals(broadcastId)) {
                    userBroadcastRepository.updateReadStatus(message.getId(), status);
                    break;
                }
            }
            
        } catch (Exception e) {
            log.error("Error updating read status: {}", e.getMessage());
            throw new RuntimeException("Failed to update read status", e);
        }
    }

    public void processPendingEvents(String userId) {
        log.info("Processing pending events for user: {}", userId);
        
        try {
            // Get pending events from cache
            List<MessageDeliveryEvent> pendingEvents = caffeineCacheService.getPendingEvents(userId);
            
            // Process each pending event
            for (MessageDeliveryEvent event : pendingEvents) {
                handleEvent(event);
            }
            
            // Clear pending events from cache
            caffeineCacheService.clearPendingEvents(userId);
            
            log.info("Processed {} pending events for user: {}", pendingEvents.size(), userId);
            
        } catch (Exception e) {
            log.error("Error processing pending events for user {}: {}", userId, e.getMessage());
        }
    }
}
