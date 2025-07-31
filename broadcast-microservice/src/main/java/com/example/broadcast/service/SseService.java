package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.repository.UserSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing Server-Sent Events (SSE) connections
 * Handles real-time message delivery to online users with sub-second latency
 */
@Service
@Slf4j
public class SseService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final UserSessionRepository userSessionRepository;
    private final ObjectMapper objectMapper;
    private final BroadcastRepository broadcastRepository;
    
    @Value("${broadcast.sse.timeout:300000}")
    private long sseTimeout;
    @Value("${broadcast.sse.heartbeat-interval:30000}")
    private long heartbeatInterval;
    
    // Store active SSE sinks by user ID
    private final Map<String, Sinks.Many<String>> userSinks = new ConcurrentHashMap<>();
    // Reactor sink for handling message events
    private final Sinks.Many<MessageDeliveryEvent> messageSink = Sinks.many().multicast().onBackpressureBuffer();
    // Scheduled executor for heartbeat and cleanup
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public SseService(UserBroadcastRepository userBroadcastRepository, UserSessionRepository userSessionRepository, ObjectMapper objectMapper, BroadcastRepository broadcastRepository) {
        this.userBroadcastRepository = userBroadcastRepository;
        this.userSessionRepository = userSessionRepository;
        this.objectMapper = objectMapper;
        this.broadcastRepository = broadcastRepository;
    }

    @PostConstruct
    public void init() {
        // Start heartbeat task
        startHeartbeat();
        // Start cleanup task
        startCleanup();
    }

    /**
     * Create reactive SSE event stream for a user
     * This establishes a real-time connection for message delivery
     */
    public Flux<String> createEventStream(String userId) {
        log.debug("Creating SSE event stream for user: {}", userId);
        // Create a new sink for this user
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        userSinks.put(userId, sink);
        
        // Send pending messages immediately
        sendPendingMessages(userId, sink);
        // Send initial connection event
        sendEvent(userId, Map.of(
                "type", "CONNECTED",
                "timestamp", ZonedDateTime.now(),
                "message", "SSE connection established"
        ));
        // Return the flux as a hot stream
        return sink.asFlux()
                .doOnCancel(() -> {
                    log.debug("SSE stream cancelled for user: {}", userId);
                    userSinks.remove(userId);
                })
                .doOnError(throwable -> {
                    log.error("SSE stream error for user {}: {}", userId, throwable.getMessage());
                    userSinks.remove(userId);
                });
    }

    /**
     * Send pending messages to a newly connected user
     */
    private void sendPendingMessages(String userId, Sinks.Many<String> sink) {
        try {
            List<UserBroadcastMessage> pendingMessages = userBroadcastRepository.findPendingMessages(userId);
            List<UserBroadcastMessage> unreadMessages = userBroadcastRepository.findUnreadMessages(userId);
            
            // Send pending messages first
            for (UserBroadcastMessage message : pendingMessages) {
                UserBroadcastResponse response = buildUserBroadcastResponse(message);
                sendEventToSink(sink, Map.of(
                        "type", "MESSAGE",
                        "data", response,
                        "timestamp", ZonedDateTime.now()
                ));
                // Mark as delivered
                userBroadcastRepository.updateDeliveryStatus(message.getId(), "DELIVERED");
            }
            
            // Send unread messages
            for (UserBroadcastMessage message : unreadMessages) {
                UserBroadcastResponse response = buildUserBroadcastResponse(message);
                sendEventToSink(sink, Map.of(
                        "type", "MESSAGE",
                        "data", response,
                        "timestamp", ZonedDateTime.now()
                ));
            }
            
            if (!pendingMessages.isEmpty() || !unreadMessages.isEmpty()) {
                log.info("Sent {} pending and {} unread messages to user: {}", 
                        pendingMessages.size(), unreadMessages.size(), userId);
            }
            
        } catch (Exception e) {
            log.error("Error sending pending messages to user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Send event to a specific user
     */
    public void sendEvent(String userId, Map<String, Object> event) {
        Sinks.Many<String> sink = userSinks.get(userId);
        if (sink != null) {
            sendEventToSink(sink, event);
        } else {
            log.debug("No active sink for user: {}", userId);
        }
    }

    /**
     * Send event to specific sink
     */
    private void sendEventToSink(Sinks.Many<String> sink, Map<String, Object> event) {
        try {
            String jsonEvent = objectMapper.writeValueAsString(event);
            sink.tryEmitNext(jsonEvent);
        } catch (Exception e) {
            log.error("Error sending SSE event: {}", e.getMessage());
            sink.tryEmitError(e);
        }
    }

    /**
     * Handle message delivery event from Kafka
     */
    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Handling message event: {} for user: {}", event.getEventType(), event.getUserId());
        // Try to deliver via SSE if user is online
        if ("CREATED".equals(event.getEventType()) || "DELIVERED".equals(event.getEventType())) {
            // For message creation, try to deliver immediately
            deliverMessageToUser(event.getUserId(), event.getBroadcastId());
        } else if ("READ".equals(event.getEventType())) {
            // For read events, update client state
            sendEvent(event.getUserId(), Map.of(
                    "type", "READ_RECEIPT",
                    "broadcastId", event.getBroadcastId(),
                    "timestamp", event.getTimestamp()
            ));
        }
    }

    /**
     * Deliver message to specific user
     */
    private void deliverMessageToUser(String userId, Long broadcastId) {
        try {
            List<UserBroadcastMessage> messages = userBroadcastRepository.findByUserIdAndStatus(
                    userId, "PENDING", "UNREAD");
            for (UserBroadcastMessage message : messages) {
                if (message.getBroadcastId().equals(broadcastId)) {
                    UserBroadcastResponse response = buildUserBroadcastResponse(message);
                    sendEvent(userId, Map.of(
                            "type", "MESSAGE",
                            "data", response,
                            "timestamp", ZonedDateTime.now()
                    ));
                    
                    // Mark as delivered
                    userBroadcastRepository.updateDeliveryStatus(message.getId(), "DELIVERED");
                    log.debug("Message delivered to user: {}, broadcast: {}", userId, broadcastId);
                    break;
                }
            }
        } catch (Exception e) {
            log.error("Error delivering message to user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Build user broadcast response
     */
    private UserBroadcastResponse buildUserBroadcastResponse(UserBroadcastMessage message) {
        BroadcastMessage broadcast = broadcastRepository.findById(message.getBroadcastId())
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + message.getBroadcastId()));
        return UserBroadcastResponse.builder()
                .id(message.getId())
                .broadcastId(message.getBroadcastId())
                .userId(message.getUserId())
                .deliveryStatus(message.getDeliveryStatus())
                .readStatus(message.getReadStatus())
                .deliveredAt(message.getDeliveredAt())
                .readAt(message.getReadAt())
                .createdAt(message.getCreatedAt())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .broadcastCreatedAt(broadcast.getCreatedAt())
                .build();
    }

    /**
     * Start heartbeat task to keep connections alive
     */
    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Map<String, Object> heartbeat = Map.of(
                        "type", "HEARTBEAT",
                        "timestamp", ZonedDateTime.now(),
                        "message", "Connection alive"
                );
                
                // Send heartbeat to all connected users
                userSinks.forEach((userId, sink) -> {
                    try {
                        sendEventToSink(sink, heartbeat);
                    } catch (Exception e) {
                        log.warn("Failed to send heartbeat to user {}: {}", userId, e.getMessage());
                        // The cleanup task will handle the removal of the stale sink
                    }
                });
                
                log.debug("Heartbeat sent to {} connected users", userSinks.size());
                
            } catch (Exception e) {
                log.error("Error in heartbeat task: {}", e.getMessage());
            }
        }, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    /**
     * Start cleanup task for expired connections to prevent memory leaks.
     */
    private void startCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 1. Clean up expired user sessions in the database
                int cleanedSessions = userSessionRepository.cleanupExpiredSessions();
                if (cleanedSessions > 0) {
                    log.info("Cleaned up {} expired user sessions from the database.", cleanedSessions);
                }

                // 2. Get all user IDs that are currently active in the database
                List<String> activeDbUsers = userSessionRepository.findAllActiveUserIds();

                // 3. Identify and remove zombie sinks from the in-memory map
                List<String> zombieSinks = userSinks.keySet().stream()
                    .filter(userId -> !activeDbUsers.contains(userId))
                    .collect(Collectors.toList());

                if (!zombieSinks.isEmpty()) {
                    log.warn("Found {} zombie SSE sinks to clean up: {}", zombieSinks.size(), zombieSinks);
                    zombieSinks.forEach(userId -> {
                        Sinks.Many<String> sink = userSinks.remove(userId);
                        if (sink != null) {
                            sink.tryEmitComplete(); // Gracefully complete the sink
                        }
                    });
                }
                
                log.debug("Cleanup completed. Active sinks: {}", userSinks.size());
                
            } catch (Exception e) {
                log.error("Error in cleanup task: {}", e.getMessage());
            }
        }, 60000, 60000, TimeUnit.MILLISECONDS); // Run every minute
    }


    /**
     * Get number of connected users
     */
    public int getConnectedUserCount() {
        return userSinks.size();
    }

    /**
     * Check if user is connected
     */
    public boolean isUserConnected(String userId) {
        return userSinks.containsKey(userId);
    }

    /**
     * Get message sink for reactive processing
     */
    public Sinks.Many<MessageDeliveryEvent> getMessageSink() {
        return messageSink;
    }

    /**
     * Get flux of message events
     */
    public Flux<MessageDeliveryEvent> getMessageEvents() {
        return messageSink.asFlux();
    }
}