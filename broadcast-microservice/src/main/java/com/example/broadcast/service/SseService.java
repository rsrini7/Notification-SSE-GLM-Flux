// broadcast-microservice/src/main/java/com/example/broadcast/service/SseService.java
package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.BroadcastStatisticsRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.repository.UserSessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class SseService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final UserSessionRepository userSessionRepository;
    private final ObjectMapper objectMapper;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    
    @Value("${broadcast.sse.heartbeat-interval:30000}")
    private long heartbeatInterval;
    private final Map<String, Sinks.Many<String>> userSinks = new ConcurrentHashMap<>();
    
    private Disposable heartbeatSubscription;
    private Disposable cleanupSubscription;

    public SseService(UserBroadcastRepository userBroadcastRepository, UserSessionRepository userSessionRepository, ObjectMapper objectMapper, BroadcastRepository broadcastRepository, BroadcastStatisticsRepository broadcastStatisticsRepository) {
        this.userBroadcastRepository = userBroadcastRepository;
        this.userSessionRepository = userSessionRepository;
        this.objectMapper = objectMapper;
        this.broadcastRepository = broadcastRepository;
        this.broadcastStatisticsRepository = broadcastStatisticsRepository;
    }

    @PostConstruct
    public void init() {
        startHeartbeat();
        startCleanup();
    }

    @PreDestroy
    public void cleanup() {
        if (heartbeatSubscription != null && !heartbeatSubscription.isDisposed()) {
            heartbeatSubscription.dispose();
        }
        if (cleanupSubscription != null && !cleanupSubscription.isDisposed()) {
            cleanupSubscription.dispose();
        }
        log.info("Disposed of SSE scheduled tasks.");
    }

    public Flux<String> createEventStream(String userId) {
        log.debug("Creating SSE event stream for user: {}", userId);
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        userSinks.put(userId, sink);
        
        sendPendingMessages(userId, sink);
        sendEvent(userId, Map.of(
                "type", "CONNECTED",
                "timestamp", ZonedDateTime.now(),
                "message", "SSE connection established"
        ));
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

    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Handling message event: {} for user: {}", event.getEventType(), event.getUserId());
        if ("CREATED".equals(event.getEventType())) {
            deliverMessageToUser(event.getUserId(), event.getBroadcastId());
        } else if ("READ".equals(event.getEventType())) {
            sendEvent(event.getUserId(), Map.of(
                    "type", "READ_RECEIPT",
                    "data", Map.of("broadcastId", event.getBroadcastId()),
                    "timestamp", event.getTimestamp()
            ));
        } else if ("EXPIRED".equals(event.getEventType()) || "CANCELLED".equals(event.getEventType())) {
            sendEvent(event.getUserId(), Map.of(
                    "type", "MESSAGE_REMOVED",
                    "data", Map.of("broadcastId", event.getBroadcastId()),
                    "timestamp", event.getTimestamp()
            ));
        }
    }

    private void sendPendingMessages(String userId, Sinks.Many<String> sink) {
        try {
            List<UserBroadcastMessage> pendingMessages = userBroadcastRepository.findPendingMessages(userId);
            for (UserBroadcastMessage message : pendingMessages) {
                UserBroadcastResponse response = buildUserBroadcastResponse(message);
                sendEventToSink(sink, Map.of(
                        "type", "MESSAGE",
                        "data", response,
                        "timestamp", ZonedDateTime.now()
                ));
                userBroadcastRepository.updateDeliveryStatus(message.getId(), "DELIVERED");
                broadcastStatisticsRepository.incrementDeliveredCount(message.getBroadcastId());
            }
            
            if (!pendingMessages.isEmpty()) {
                log.info("Sent {} pending messages to user: {}", pendingMessages.size(), userId);
            }
            
        } catch (Exception e) {
            log.error("Error sending pending messages to user {}: {}", userId, e.getMessage());
        }
    }

    public void sendEvent(String userId, Map<String, Object> event) {
        Sinks.Many<String> sink = userSinks.get(userId);
        if (sink != null) {
            sendEventToSink(sink, event);
        } else {
            log.debug("No active sink for user: {}", userId);
        }
    }

    private void sendEventToSink(Sinks.Many<String> sink, Map<String, Object> event) {
        try {
            String jsonEvent = objectMapper.writeValueAsString(event);
            sink.tryEmitNext(jsonEvent);
        } catch (Exception e) {
            log.error("Error sending SSE event: {}", e.getMessage());
            sink.tryEmitError(e);
        }
    }

    private void deliverMessageToUser(String userId, Long broadcastId) {
        try {
            List<UserBroadcastMessage> messages = userBroadcastRepository.findPendingMessagesByBroadcastId(userId, broadcastId);
            if (!messages.isEmpty()) {
                UserBroadcastMessage message = messages.get(0); // Should only be one
                UserBroadcastResponse response = buildUserBroadcastResponse(message);
                sendEvent(userId, Map.of(
                        "type", "MESSAGE",
                        "data", response,
                        "timestamp", ZonedDateTime.now()
                ));
                userBroadcastRepository.updateDeliveryStatus(message.getId(), "DELIVERED");
                broadcastStatisticsRepository.incrementDeliveredCount(broadcastId);
                log.info("Message delivered to online user: {}, broadcast: {}", userId, broadcastId);
            } else {
                log.warn("Could not find PENDING message for user {} and broadcast {}, it might have been delivered already.", userId, broadcastId);
            }
        } catch (Exception e) {
            log.error("Error delivering message to user {}: {}", userId, e.getMessage());
        }
    }

    private UserBroadcastResponse buildUserBroadcastResponse(UserBroadcastMessage message) {
        BroadcastMessage broadcast = broadcastRepository.findById(message.getBroadcastId())
                .orElseThrow(() -> new RuntimeException("Broadcast not found: " + message.getBroadcastId()));
        return UserBroadcastResponse.builder()
                .id(message.getId())
                .broadcastId(message.getBroadcastId())
                .userId(message.getUserId())
                .deliveryStatus("DELIVERED") // We are delivering it now
                .readStatus(message.getReadStatus())
                .deliveredAt(message.getDeliveredAt())
                .readAt(message.getReadAt())
                .createdAt(message.getCreatedAt())
                .senderName(broadcast.getSenderName())
                .content(broadcast.getContent())
                .priority(broadcast.getPriority())
                .category(broadcast.getCategory())
                .broadcastCreatedAt(broadcast.getCreatedAt())
                .expiresAt(broadcast.getExpiresAt())
                .build();
    }

    private void startHeartbeat() {
        heartbeatSubscription = Flux.interval(Duration.ofMillis(heartbeatInterval), Schedulers.parallel())
            .doOnNext(tick -> {
                try {
                    Map<String, Object> heartbeat = Map.of(
                            "type", "HEARTBEAT",
                            "timestamp", ZonedDateTime.now(),
                            "message", "Connection alive"
                    );

                    userSinks.forEach((userId, sink) -> {
                        try {
                            sendEventToSink(sink, heartbeat);
                        } catch (Exception e) {
                            log.warn("Failed to send heartbeat to user {}: {}", userId, e.getMessage());
                        }
                    });
                    
                    log.debug("Heartbeat sent to {} connected users", userSinks.size());
                } catch (Exception e) {
                    log.error("Error in heartbeat task: {}", e.getMessage());
                }
            })
            .subscribe();
    }

    private void startCleanup() {
        cleanupSubscription = Flux.interval(Duration.ofSeconds(60), Schedulers.parallel())
            .doOnNext(tick -> {
                 try {
                    int cleanedSessions = userSessionRepository.cleanupExpiredSessions();
                    if (cleanedSessions > 0) {
                        log.info("Cleaned up {} expired user sessions from the database.", cleanedSessions);
                    }

                    List<String> activeDbUsers = userSessionRepository.findAllActiveUserIds();
                    List<String> zombieSinks = userSinks.keySet().stream()
                        .filter(userId -> !activeDbUsers.contains(userId))
                        .collect(Collectors.toList());

                    if (!zombieSinks.isEmpty()) {
                        log.warn("Found {} zombie SSE sinks to clean up: {}", zombieSinks.size(), zombieSinks);
                        zombieSinks.forEach(userId -> {
                            Sinks.Many<String> sink = userSinks.remove(userId);
                            if (sink != null) {
                                sink.tryEmitComplete();
                            }
                        });
                    }
                    
                    log.debug("Cleanup completed. Active sinks: {}", userSinks.size());
                } catch (Exception e) {
                    log.error("Error in cleanup task: {}", e.getMessage());
                }
            })
            .subscribe();
    }

    public int getConnectedUserCount() {
        return userSinks.size();
    }

    public boolean isUserConnected(String userId) {
        return userSinks.containsKey(userId);
    }
}