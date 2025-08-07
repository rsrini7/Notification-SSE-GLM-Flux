package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.service.cache.CacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the low-level technical aspects of Server-Sent Event (SSE) connections.
 * This class is the single source of truth for in-memory connection state on this pod.
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

    private final Map<String, Sinks.Many<ServerSentEvent<String>>> userSinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userSessionMap = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToUserIdMap = new ConcurrentHashMap<>();

    private final DistributedSessionManager sessionManager;
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
    public void registerConnection(String userId, String sessionId) {
        sessionManager.registerSession(userId, sessionId, appProperties.getPod().getId());
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
    public void removeEventStream(String userId, String sessionId) {
        Sinks.Many<ServerSentEvent<String>> sink = userSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
            Set<String> sessions = userSessionMap.get(userId);
            if (sessions != null) {
                sessions.remove(sessionId);
                if (sessions.isEmpty()) {
                    userSessionMap.remove(userId);
                }
            }
            sessionIdToUserIdMap.remove(sessionId);
            sessionManager.removeSession(userId, sessionId, appProperties.getPod().getId());
            cacheService.unregisterUserConnection(userId, sessionId);
            log.info("Cleanly disconnected session {} for user {}", sessionId, userId);
        }
    }

    /**
     * Scheduled job to find and mark stale sessions as INACTIVE across the cluster.
     * It also cleans up any corresponding in-memory sinks on the current pod.
     */
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "cleanupStaleSseSessions", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void cleanupStaleSessions() {
        try {
            long staleThresholdSeconds = (appProperties.getSse().getHeartbeatInterval() / 1000) * 3;
            long thresholdTimestamp = ZonedDateTime.now().minusSeconds(staleThresholdSeconds).toEpochSecond();
            Set<String> staleSessionIds = sessionManager.getStaleSessionIds(thresholdTimestamp);
            if (staleSessionIds.isEmpty()) return;

            log.warn("Found {} total stale sessions cluster-wide to clean up.", staleSessionIds.size());

            for (String sessionId : staleSessionIds) {
                sessionManager.getSessionDetails(sessionId).ifPresent(details -> {
                    if (appProperties.getPod().getId().equals(details.getPodId())) {
                        removeEventStream(details.getUserId(), sessionId);
                    } else {
                        cacheService.unregisterUserConnection(details.getUserId(), sessionId);
                    }
                });
            }
            sessionManager.removeSessions(staleSessionIds);
        } catch (Exception e) {
            log.error("Error during stale session cleanup task: {}", e.getMessage(), e);
        }
    }

    private void startServerHeartbeat() {
        serverHeartbeatSubscription = Flux.interval(Duration.ofMillis(appProperties.getSse().getHeartbeatInterval()), Schedulers.parallel())
            .doOnNext(tick -> {
                try {
                    Set<String> sessionIdsOnThisPod = userSinks.keySet();
                    if (sessionIdsOnThisPod.isEmpty()) return;
                    
                    sessionManager.updateHeartbeats(appProperties.getPod().getId(), sessionIdsOnThisPod);

                    String payload = objectMapper.writeValueAsString(Map.of("timestamp", ZonedDateTime.now()));
                    ServerSentEvent<String> heartbeatEvent = ServerSentEvent.<String>builder()
                            .event("HEARTBEAT").data(payload).build();

                    for (String sessionId : sessionIdsOnThisPod) {
                        String userId = sessionIdToUserIdMap.get(sessionId);
                        if (userId != null) {
                            sendEvent(userId, heartbeatEvent);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error in server heartbeat task: {}", e.getMessage());
                }
            })
            .subscribe();
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