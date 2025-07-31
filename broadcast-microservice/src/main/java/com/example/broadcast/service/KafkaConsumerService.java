package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.UserBroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kafka consumer service for processing broadcast events
 * Handles message delivery, read receipts, and other events with at-least-once semantics
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final UserBroadcastRepository userBroadcastRepository;
    private final CaffeineCacheService caffeineCacheService;
    
    // Thread pool for async processing
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    /**
     * Process broadcast events from Kafka
     * This listener handles all types of broadcast events with proper error handling
     */
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
            @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp,
            Acknowledgment acknowledgment) {
        
        log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}", 
                event.getEventId(), topic, partition, offset);
        
        try {
            // Process event asynchronously to avoid blocking the consumer
            CompletableFuture.runAsync(() -> {
                try {
                    handleEvent(event);
                    acknowledgment.acknowledge();
                    log.debug("Event processed and acknowledged: {}", event.getEventId());
                } catch (Exception e) {
                    log.error("Error processing event {}: {}", event.getEventId(), e.getMessage());
                    // Don't acknowledge - let Kafka retry
                    throw new RuntimeException("Event processing failed", e);
                }
            }, executorService);
            
        } catch (Exception e) {
            log.error("Error submitting event for processing: {}", e.getMessage());
            // Don't acknowledge - let Kafka retry
        }
    }

    /**
     * Handle different types of broadcast events
     */
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
            default:
                log.warn("Unknown event type: {}", event.getEventType());
        }
    }

    /**
     * Handle broadcast creation event
     * This ensures messages are delivered to online users immediately
     */
    private void handleBroadcastCreated(MessageDeliveryEvent event) {
        log.info("Handling broadcast created event for user: {}, broadcast: {}", 
                event.getUserId(), event.getBroadcastId());
        
        try {
            // Check if user is online (via cache or SSE service)
            boolean isOnline = caffeineCacheService.isUserOnline(event.getUserId()) || 
                              sseService.isUserConnected(event.getUserId());
            
            if (isOnline) {
                // Deliver message immediately via SSE
                sseService.handleMessageEvent(event);
                
                // Update delivery status in database
                updateDeliveryStatus(event.getBroadcastId(), event.getUserId(), "DELIVERED");
                
                log.info("Broadcast delivered to online user: {}", event.getUserId());
            } else {
                // User is offline - message remains pending
                log.info("User {} is offline, message remains pending", event.getUserId());
                
                // Cache the event for when user comes online
                caffeineCacheService.cachePendingEvent(event);
            }
            
        } catch (Exception e) {
            log.error("Error handling broadcast created event: {}", e.getMessage());
            throw new RuntimeException("Failed to handle broadcast created event", e);
        }
    }

    /**
     * Handle message delivery event
     */
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

    /**
     * Handle message read event
     */
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

    /**
     * Handle delivery failed event
     */
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

    /**
     * Handle broadcast cancelled event
     */
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

    /**
     * Update delivery status in database
     */
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

    /**
     * Update read status in database
     */
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

    /**
     * Process pending events for a user when they come online
     * This is called when a user reconnects or logs in
     */
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