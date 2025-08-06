package com.example.broadcast.service;

import com.example.broadcast.config.AppProperties;
import com.example.broadcast.model.UserSession;
import com.example.broadcast.repository.UserSessionRepository;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;


/**
 * Manages the low-level technical aspects of Server-Sent Event (SSE) connections.
 * Its responsibilities include:
 * - Creating and storing in-memory sinks for each client connection.
 * - Tracking active user-to-session mappings.
 * - Persisting session state to the database.
 * - Sending periodic heartbeats to keep connections alive.
 * - Cleaning up stale or disconnected sessions from memory and the database.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SseConnectionManager {

    // In-memory state for active connections on this specific pod
    private final Map<String, Sinks.Many<ServerSentEvent<String>>> userSinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userSessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToUserIdMap = new ConcurrentHashMap<>();

    private final UserSessionRepository userSessionRepository;
    private final CacheService cacheService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;

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

    /**
     * Creates an in-memory sink and a corresponding Flux stream for a new client connection.
     * This method also handles the cleanup logic for when the connection is terminated.
     *
     * @param userId    The ID of the connecting user.
     * @param sessionId The unique ID for this specific session.
     * @return A Flux of ServerSentEvent that the client can subscribe to.
     */
    public Flux<ServerSentEvent<String>> createEventStream(String userId, String sessionId) {
        log.debug("Creating SSE event stream for user: {}, session: {}", userId, sessionId);
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();

        userSinks.put(sessionId, sink);
        userSessionMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
        sessionIdToUserIdMap.put(sessionId, userId);

        return sink.asFlux()
                .doOnCancel(() -> removeEventStream(userId, sessionId))
                .doOnError(throwable -> removeEventStream(userId, sessionId))
                .doOnTerminate(() -> removeEventStream(userId, sessionId));
    }

    /**
     * Persists the user's session to the database and registers it in the cache.
     *
     * @param userId    The user's ID.
     * @param sessionId The session's unique ID.
     */
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

    /**
     * Removes a user's session from in-memory maps and updates its status
     * in the database and cache to INACTIVE.
     *
     * @param userId    The user's ID.
     * @param sessionId The session's unique ID.
     */
    @Transactional
    public void removeEventStream(String userId, String sessionId) {
        boolean wasRemoved = false;
        Set<String> sessions = userSessionMap.get(userId);
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
    
    /**
     * Pushes an event to all active sessions for a given user.
     * If emitting an event fails, it triggers an asynchronous cleanup of the failed session.
     *
     * @param userId The ID of the user to send the event to.
     * @param event  The ServerSentEvent to send.
     */
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

    /**
     * Scheduled job to find and mark stale sessions as INACTIVE across the cluster.
     * It also cleans up any corresponding in-memory sinks on the current pod.
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void cleanupStaleSessions() {
        try {
            long staleThresholdSeconds = (appProperties.getSse().getHeartbeatInterval() / 1000) * 3;
            ZonedDateTime threshold = ZonedDateTime.now().minusSeconds(staleThresholdSeconds);

            List<UserSession> allStaleSessions = userSessionRepository.findStaleSessions(threshold);
            if (allStaleSessions.isEmpty()) return;

            // Step 1: Clean up in-memory sinks for stale sessions that belong to THIS pod
            List<UserSession> staleSessionsOnThisPod = allStaleSessions.stream()
                    .filter(session -> appProperties.getPod().getId().equals(session.getPodId()))
                    .collect(Collectors.toList());

            if (!staleSessionsOnThisPod.isEmpty()) {
                log.warn("Found {} stale sessions on this pod ({}) to clean up from memory.", staleSessionsOnThisPod.size(), appProperties.getPod().getId());
                for (UserSession staleSession : staleSessionsOnThisPod) {
                    removeEventStream(staleSession.getUserId(), staleSession.getSessionId());
                }
            }
            
            // Step 2: Mark ALL stale sessions as INACTIVE in the database
            log.warn("Found {} total stale sessions cluster-wide to mark as INACTIVE.", allStaleSessions.size());
            List<String> staleUserIds = allStaleSessions.stream()
                    .map(UserSession::getUserId)
                    .distinct()
                    .collect(Collectors.toList());
            userSessionRepository.markSessionsInactiveForUsers(staleUserIds);
            log.info("Marked {} users as INACTIVE in the database.", staleUserIds.size());

            // Step 3: Remove all stale sessions from the cache
            for (UserSession staleSession : allStaleSessions) {
                cacheService.unregisterUserConnection(staleSession.getUserId(), staleSession.getSessionId());
            }
            log.info("Removed {} stale sessions from the cache.", allStaleSessions.size());
        } catch (Exception e) {
            log.error("Error during stale session cleanup task: {}", e.getMessage(), e);
        }
    }

    private void startServerHeartbeat() {
        serverHeartbeatSubscription = Flux.interval(Duration.ofMillis(appProperties.getSse().getHeartbeatInterval()), Schedulers.parallel())
                .doOnNext(tick -> {
                    try {
                        String payload = objectMapper.writeValueAsString(Map.of("timestamp", ZonedDateTime.now()));
                        ServerSentEvent<String> heartbeatEvent = ServerSentEvent.<String>builder()
                                .event("HEARTBEAT")
                                .data(payload)
                                .build();
                        
                        new ArrayList<>(userSinks.keySet()).forEach(sessionId -> {
                            String userId = sessionIdToUserIdMap.get(sessionId);
                            if (userId != null) {
                                sendEvent(userId, heartbeatEvent);
                            }
                        });
                    } catch (Exception e) {
                        log.error("Error in server heartbeat task: {}", e.getMessage());
                    }
                })
                .subscribe();
    }

    private void cleanupFailedSessionAsync(String userId, String sessionId) {
        Schedulers.boundedElastic().schedule(() -> removeEventStream(userId, sessionId));
    }

    public int getConnectedUserCount() {
        return userSinks.size();
    }

    public boolean isUserConnected(String userId) {
        Set<String> sessions = userSessionMap.get(userId);
        return sessions != null && !sessions.isEmpty();
    }
}