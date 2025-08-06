package com.example.broadcast.service;

import com.example.broadcast.config.AppProperties;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.mapper.BroadcastMapper;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.model.UserSession;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.repository.UserSessionRepository;
import com.example.broadcast.util.Constants.DeliveryStatus;
import com.example.broadcast.util.Constants.EventType;
import com.example.broadcast.util.Constants.SseEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SseService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final UserSessionRepository userSessionRepository;
    private final ObjectMapper objectMapper;
    private final BroadcastRepository broadcastRepository;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService;
    private final AppProperties appProperties;
 
    // --- REFACTORED DEPENDENCY ---
    private final BroadcastMapper broadcastMapper;
    
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> userSinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userSessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToUserIdMap = new ConcurrentHashMap<>();

    private Disposable serverHeartbeatSubscription;

    @PostConstruct
    public void init() {
        startServerHeartbeat();
    }

    @PreDestroy
    public void cleanup() {
        if (serverHeartbeatSubscription != null && !serverHeartbeatSubscription.isDisposed()) {
            serverHeartbeatSubscription.dispose();
        }
    }

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanupStaleSessions() {
         try {
            long staleThresholdSeconds = (appProperties.getSse().getHeartbeatInterval() / 1000) * 3;
            ZonedDateTime threshold = ZonedDateTime.now().minusSeconds(staleThresholdSeconds);
            
            List<UserSession> allStaleSessions = userSessionRepository.findStaleSessions(threshold);

            if (allStaleSessions.isEmpty()) {
                return;
            }

            List<UserSession> staleSessionsOnThisPod = allStaleSessions.stream()
                    .filter(session -> appProperties.getPod().getId().equals(session.getPodId()))
                    .collect(Collectors.toList());
            if (!staleSessionsOnThisPod.isEmpty()) {
                log.warn("Found {} stale sessions on this pod ({}) to clean up from memory.", staleSessionsOnThisPod.size(), appProperties.getPod().getId());
                for (UserSession staleSession : staleSessionsOnThisPod) {
                    Sinks.Many<ServerSentEvent<String>> sink = userSinks.remove(staleSession.getSessionId());
                    if (sink != null) {
                        sink.tryEmitComplete();
                        log.info("Removed stale in-memory sink for session: {}", staleSession.getSessionId());
                    }
                    Set<String> sessions = userSessionMap.get(staleSession.getUserId());
                    if (sessions != null) {
                        sessions.remove(staleSession.getSessionId());
                        if (sessions.isEmpty()) {
                            userSessionMap.remove(staleSession.getUserId());
                        }
                    }
                    sessionIdToUserIdMap.remove(staleSession.getSessionId());
                }
            }
            
            log.warn("Found {} total stale sessions cluster-wide to mark as INACTIVE.", allStaleSessions.size());
            List<String> staleUserIds = allStaleSessions.stream()
                    .map(UserSession::getUserId)
                    .distinct()
                    .collect(Collectors.toList());
            userSessionRepository.markSessionsInactiveForUsers(staleUserIds);
            log.info("Marked {} users as INACTIVE in the database.", staleUserIds.size());
            for (UserSession staleSession : allStaleSessions) {
                cacheService.unregisterUserConnection(staleSession.getUserId(), staleSession.getSessionId());
            }
            log.info("Removed {} stale sessions from the cache.", allStaleSessions.size());
        } catch (Exception e) {
            log.error("Error during stale session cleanup task: {}", e.getMessage(), e);
        }
    }

    @Transactional
    public void registerConnection(String userId, String sessionId) {
        UserSession session = UserSession.builder()
            .userId(userId)
            .sessionId(sessionId)
            .podId(appProperties.getPod().getId())
            .connectionStatus("ACTIVE")
            .connectedAt(ZonedDateTime.now(ZoneOffset.UTC))
            .lastHeartbeat(ZonedDateTime.now(ZoneOffset.UTC))
            .build();
        userSessionRepository.save(session);
        cacheService.registerUserConnection(userId, sessionId, appProperties.getPod().getId());
        log.info("User session saved for user: {}, session: {}", userId, sessionId);
    }
    
    public Flux<ServerSentEvent<String>> createEventStream(String userId, String sessionId) {
        log.debug("Creating SSE event stream for user: {}, session: {}", userId, sessionId);
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        
        userSinks.put(sessionId, sink);
        userSessionMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionIdToUserIdMap.put(sessionId, userId);

        sendPendingMessages(userId);
        try {
            String connectedPayload = objectMapper.writeValueAsString(Map.of("message", "SSE connection established with session " + sessionId));
            sendEvent(userId, ServerSentEvent.<String>builder()
                .event(SseEventType.CONNECTED.name())
                .data(connectedPayload)
                .build());
        } catch (JsonProcessingException e) {
            log.error("Error creating CONNECTED event", e);
        }
        
        return sink.asFlux()
                .doOnCancel(() -> removeEventStream(userId, sessionId))
                .doOnError(throwable -> removeEventStream(userId, sessionId))
                .doOnTerminate(() -> removeEventStream(userId, sessionId));
    }

    @Transactional
    public void removeEventStream(String userId, String sessionId) {
        Set<String> sessions = userSessionMap.get(userId);
        boolean wasRemoved = false;
        if (sessions != null) {
            wasRemoved = sessions.remove(sessionId);
            if (sessions.isEmpty()) {
                userSessionMap.remove(userId);
            }
        }
        
        if (wasRemoved) {
            userSinks.remove(sessionId);
            sessionIdToUserIdMap.remove(sessionId);
            int updated = userSessionRepository.markSessionInactive(sessionId, appProperties.getPod().getId());
            if (updated > 0) {
                cacheService.unregisterUserConnection(userId, sessionId);
                log.info("Cleanly disconnected session {} for user {}", sessionId, userId);
            }
        }
    }

    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Handling message event: {} for user: {}", event.getEventType(), event.getUserId());
        try {
            String payload = objectMapper.writeValueAsString(Map.of("broadcastId", event.getBroadcastId()));
            switch (EventType.valueOf(event.getEventType())) {
                case CREATED:
                    deliverMessageToUser(event.getUserId(), event.getBroadcastId());
                    break;
                case READ:
                case EXPIRED:
                case CANCELLED:
                    sendEvent(event.getUserId(), ServerSentEvent.<String>builder().event(SseEventType.MESSAGE_REMOVED.name()).data(payload).build());
                    break;
            }
        } catch (JsonProcessingException e) {
            log.error("Error processing message event for SSE", e);
        }
    }


    private void sendPendingMessages(String userId) {
        List<UserBroadcastMessage> pendingMessages = userBroadcastRepository.findPendingMessages(userId);
        for (UserBroadcastMessage message : pendingMessages) {
            deliverMessageToUser(userId, message.getBroadcastId());
        }
        if (!pendingMessages.isEmpty()) {
            log.info("Sent {} pending messages to user: {}", pendingMessages.size(), userId);
        }
    }

    public void sendEvent(String userId, ServerSentEvent<String> event) {
        Set<String> sessionIds = userSessionMap.get(userId);
        if (sessionIds != null && !sessionIds.isEmpty()) {
            for (String sessionId : sessionIds) {
                Sinks.Many<ServerSentEvent<String>> sink = userSinks.get(sessionId);
                if (sink != null) {
                    Sinks.EmitResult result = sink.tryEmitNext(event);
                    if (result.isFailure()) {
                        log.warn("Failed to emit SSE event for user {}, session {}. Result: {}. Proactively cleaning up stale connection.", userId, sessionId, result);
                        cleanupFailedSessionAsync(userId, sessionId);
                    }
                }
            }
        }
    }
    
    private void cleanupFailedSessionAsync(String userId, String sessionId) {
        Schedulers.boundedElastic().schedule(() -> removeEventStream(userId, sessionId));
    }

    @Transactional
    public void deliverMessageToUser(String userId, Long broadcastId) {
        UserBroadcastMessage message = userBroadcastRepository
                .findByUserIdAndBroadcastId(userId, broadcastId)
                .filter(msg -> msg.getDeliveryStatus().equals(DeliveryStatus.PENDING.name()))
                .orElse(null);

        if (message == null) {
            log.warn("Skipping delivery. No PENDING UserBroadcastMessage found for user {} and broadcast {}", userId, broadcastId);
            return;
        }

        buildUserBroadcastResponse(message)
            .ifPresent(response -> {
                try {
                    String payload = objectMapper.writeValueAsString(response);
                    ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                        .event(SseEventType.MESSAGE.name())
                        .data(payload)
                        .id(String.valueOf(response.getId()))
                        .build();
                    
                    sendEvent(userId, sse);
                    
                    if (isUserConnected(userId)) {
                        messageStatusService.updateMessageToDelivered(message.getId(), broadcastId);
                        log.info("Message delivered to online user: {}, broadcast: {}", userId, broadcastId);
                    } else {
                        log.warn("Delivery attempt for user {} and broadcast {} aborted, user disconnected during process.", userId, broadcastId);
                    }

                } catch (JsonProcessingException e) {
                    log.error("Error delivering message to user as SSE", e);
                }
            });
    }

    private Optional<UserBroadcastResponse> buildUserBroadcastResponse(UserBroadcastMessage message) {
        // REFACTORED: Delegate mapping to the central mapper
        return broadcastRepository.findById(message.getBroadcastId())
            .map(broadcast -> broadcastMapper.toUserBroadcastResponse(message, broadcast));
    }

    // This private method is now removed as its logic is in BroadcastMapper
    // private UserBroadcastResponse buildUserBroadcastResponse(UserBroadcastMessage message, BroadcastMessage broadcast) { ... }
    
    // ... (rest of the methods from startServerHeartbeat onwards remain the same) ...
    private void startServerHeartbeat() {
        serverHeartbeatSubscription = Flux.interval(Duration.ofMillis(appProperties.getSse().getHeartbeatInterval()), Schedulers.parallel())
            .doOnNext(tick -> {
                try {
                    String payload = objectMapper.writeValueAsString(Map.of("timestamp", ZonedDateTime.now()));
                    ServerSentEvent<String> heartbeatEvent = ServerSentEvent.<String>builder()
                        .event(SseEventType.HEARTBEAT.name())
                        .data(payload)
                        .build();
                    
                    new ArrayList<>(userSinks.keySet()).forEach(sessionId -> {
                        String userId = sessionIdToUserIdMap.get(sessionId);
                        if (userId != null) {
                            Sinks.Many<ServerSentEvent<String>> sink = userSinks.get(sessionId);
                            if (sink != null) {
                                sink.tryEmitNext(heartbeatEvent);
                            }
                        }
                    });
                } catch (Exception e) {
                    log.error("Error in server heartbeat task: {}", e.getMessage());
                }
            })
            .subscribe();
    }

    public int getConnectedUserCount() {
        return userSinks.size();
    }

    public boolean isUserConnected(String userId) {
        Set<String> sessions = userSessionMap.get(userId);
        return sessions != null && !sessions.isEmpty();
    }
}