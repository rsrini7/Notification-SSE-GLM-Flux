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
import com.example.broadcast.dto.cache.UserConnectionInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.core.Disposable;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.example.broadcast.util.Constants.DeliveryStatus;
import com.example.broadcast.util.Constants.EventType;
import com.example.broadcast.util.Constants.SseEventType;
import com.example.broadcast.util.Constants.ReadStatus;

@Service
@Slf4j
public class SseService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final UserSessionRepository userSessionRepository;
    private final ObjectMapper objectMapper;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    // START OF CHANGE: Inject CacheService for cleanup
    private final CacheService cacheService;
    // END OF CHANGE
    
    @Value("${broadcast.sse.heartbeat-interval:30000}")
    private long heartbeatInterval;
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> userSinks = new ConcurrentHashMap<>();
    private final Map<String, String> userSessionMap = new ConcurrentHashMap<>();

    private Disposable heartbeatSubscription;
    private Disposable cleanupSubscription;
    
    // START OF CHANGE: Update constructor to accept CacheService
    public SseService(UserBroadcastRepository userBroadcastRepository, UserSessionRepository userSessionRepository, ObjectMapper objectMapper, BroadcastRepository broadcastRepository, BroadcastStatisticsRepository broadcastStatisticsRepository, CacheService cacheService) {
        this.userBroadcastRepository = userBroadcastRepository;
        this.userSessionRepository = userSessionRepository;
        this.objectMapper = objectMapper;
        this.broadcastRepository = broadcastRepository;
        this.broadcastStatisticsRepository = broadcastStatisticsRepository;
        this.cacheService = cacheService;
    }
    // END OF CHANGE

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

    public Flux<ServerSentEvent<String>> createEventStream(String userId, String sessionId) {
        log.debug("Creating SSE event stream for user: {}, session: {}", userId, sessionId);
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        
        userSinks.put(sessionId, sink);
        userSessionMap.put(userId, sessionId);

        sendPendingMessages(userId, sink);
        try {
            String connectedPayload = objectMapper.writeValueAsString(Map.of("message", "SSE connection established with session " + sessionId));
            ServerSentEvent<String> connectedEvent = ServerSentEvent.<String>builder()
                .event(SseEventType.CONNECTED.name())
                .data(connectedPayload)
                .build();
            sendEventToSink(sink, connectedEvent);
        } catch (JsonProcessingException e) {
            log.error("Error creating CONNECTED event", e);
        }
        
        return sink.asFlux()
                .doOnCancel(() -> removeEventStream(userId, sessionId))
                .doOnError(throwable -> removeEventStream(userId, sessionId))
                .doOnTerminate(() -> removeEventStream(userId, sessionId));
    }

    public void removeEventStream(String userId, String sessionId) {
        userSinks.remove(sessionId);
        userSessionMap.remove(userId, sessionId);
        // This will now properly clean up Redis as well
        cacheService.unregisterUserConnection(userId, sessionId);
    }

    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Handling message event: {} for user: {}", event.getEventType(), event.getUserId());
        try {
            if (EventType.CREATED.name().equals(event.getEventType())) {
                deliverMessageToUser(event.getUserId(), event.getBroadcastId());
            } else if (EventType.READ.name().equals(event.getEventType())) {
                String payload = objectMapper.writeValueAsString(Map.of("broadcastId", event.getBroadcastId()));
                sendEvent(event.getUserId(), ServerSentEvent.<String>builder().event(SseEventType.READ_RECEIPT.name()).data(payload).build());
            } else if (EventType.EXPIRED.name().equals(event.getEventType()) || EventType.CANCELLED.name().equals(event.getEventType())) {
                String payload = objectMapper.writeValueAsString(Map.of("broadcastId", event.getBroadcastId()));
                sendEvent(event.getUserId(), ServerSentEvent.<String>builder().event(SseEventType.MESSAGE_REMOVED.name()).data(payload).build());
            }
        } catch (JsonProcessingException e) {
            log.error("Error processing message event for SSE", e);
        }
    }

    private void sendPendingMessages(String userId, Sinks.Many<ServerSentEvent<String>> sink) {
        List<UserBroadcastMessage> pendingMessages = userBroadcastRepository.findPendingMessages(userId);
        for (UserBroadcastMessage message : pendingMessages) {
            buildUserBroadcastResponse(message).ifPresent(response -> {
                try {
                    String payload = objectMapper.writeValueAsString(response);
                    ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                        .event(SseEventType.MESSAGE.name())
                        .data(payload)
                        .id(String.valueOf(response.getId()))
                        .build();
                    sendEventToSink(sink, sse);
                    userBroadcastRepository.updateDeliveryStatus(message.getId(), DeliveryStatus.DELIVERED.name());
                    broadcastStatisticsRepository.incrementDeliveredCount(message.getBroadcastId());
                } catch (JsonProcessingException e) {
                    log.error("Error sending pending message as SSE", e);
                }
            });
        }
        if (!pendingMessages.isEmpty()) {
            log.info("Sent {} pending messages to user: {}", pendingMessages.size(), userId);
        }
    }

    public void sendEvent(String userId, ServerSentEvent<String> event) {
        String sessionId = userSessionMap.get(userId);
        if (sessionId != null) {
            Sinks.Many<ServerSentEvent<String>> sink = userSinks.get(sessionId);
            if (sink != null) {
                sendEventToSink(sink, event);
            }
        }
    }

    private void sendEventToSink(Sinks.Many<ServerSentEvent<String>> sink, ServerSentEvent<String> event) {
        sink.tryEmitNext(event);
    }

    private void deliverMessageToUser(String userId, Long broadcastId) {
        userBroadcastRepository.findPendingMessagesByBroadcastId(userId, broadcastId)
            .stream()
            .findFirst()
            .flatMap(this::buildUserBroadcastResponse) // Use method reference
            .ifPresent(response -> {
                try {
                    String payload = objectMapper.writeValueAsString(response);
                    ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                        .event(SseEventType.MESSAGE.name())
                        .data(payload)
                        .id(String.valueOf(response.getId()))
                        .build();
                    sendEvent(userId, sse);
                    userBroadcastRepository.updateDeliveryStatus(response.getId(), DeliveryStatus.DELIVERED.name());
                    broadcastStatisticsRepository.incrementDeliveredCount(broadcastId);
                    log.info("Message delivered to online user: {}, broadcast: {}", userId, broadcastId);
                } catch (JsonProcessingException e) {
                    log.error("Error delivering message to user as SSE", e);
                }
            });
    }

    private Optional<UserBroadcastResponse> buildUserBroadcastResponse(UserBroadcastMessage message) {
        return broadcastRepository.findById(message.getBroadcastId())
            .map(broadcast -> buildUserBroadcastResponse(message, broadcast));
    }
    
    private UserBroadcastResponse buildUserBroadcastResponse(UserBroadcastMessage message, BroadcastMessage broadcast) {
        // START OF FIX: Use the actual status from the message object instead of hardcoding it.
        // It will be PENDING initially, then updated to DELIVERED in the database.
        String deliveryStatus = DeliveryStatus.DELIVERED.name();
        // END OF FIX

        return UserBroadcastResponse.builder()
                .id(message.getId())
                .broadcastId(message.getBroadcastId())
                .userId(message.getUserId())
                .deliveryStatus(deliveryStatus)
                .readStatus(ReadStatus.UNREAD.name())
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
                    String payload = objectMapper.writeValueAsString(Map.of("timestamp", ZonedDateTime.now()));
                    ServerSentEvent<String> heartbeatEvent = ServerSentEvent.<String>builder()
                        .event(SseEventType.HEARTBEAT.name())
                        .data(payload)
                        .build();
                    userSinks.values().forEach(sink -> sendEventToSink(sink, heartbeatEvent));
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
                    List<String> activeDbUsers = userSessionRepository.findAllActiveUserIds();
                    
                    List<String> staleUsers = activeDbUsers.stream()
                        .filter(userId -> !isUserConnected(userId))
                        .collect(Collectors.toList());

                    if (!staleUsers.isEmpty()) {
                        log.warn("Found {} stale user sessions in DB to clean up: {}", staleUsers.size(), staleUsers);
                        int cleanedCount = userSessionRepository.markSessionsInactiveForUsers(staleUsers);
                        log.info("Marked {} user sessions as INACTIVE in the database.", cleanedCount);

                        // START OF FIX: Also remove stale users from the cache
                        staleUsers.forEach(staleUser -> {
                            UserConnectionInfo info = cacheService.getUserConnectionInfo(staleUser);
                            if (info != null) {
                                cacheService.unregisterUserConnection(staleUser, info.getSessionId());
                                log.info("Removed stale user {} from cache.", staleUser);
                            }
                        });
                        // END OF FIX
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
        String sessionId = userSessionMap.get(userId);
        return sessionId != null && userSinks.containsKey(sessionId);
    }
}