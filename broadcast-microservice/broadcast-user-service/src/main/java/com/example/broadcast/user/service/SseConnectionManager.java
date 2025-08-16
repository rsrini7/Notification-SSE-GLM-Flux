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
 * - Tracking active user-to-connection mappings.
 * - Persisting connection state.
 * - Sending periodic heartbeats to keep connections alive.
 * - Cleaning up stale or disconnected connections from memory and the distributed store.
 */
// CHANGED: Terminology updated from "session" to "connection"
@Service
@Slf4j
@RequiredArgsConstructor
public class SseConnectionManager {

    private final Map<String, Sinks.Many<ServerSentEvent<String>>> userSinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userConnectionMap = new ConcurrentHashMap<>();
    private final Map<String, String> connectionIdToUserIdMap = new ConcurrentHashMap<>();

    private final DistributedConnectionManager connectionManager;
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
     * @param userId       The ID of the connecting user.
     * @param connectionId The unique ID for this specific connection.
     * @return A Flux of ServerSentEvent that the client can subscribe to.
     */
    public Flux<ServerSentEvent<String>> createEventStream(String userId, String connectionId) {
        log.debug("Creating SSE event stream for user: {}, connection: {}", userId, connectionId);
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();
        userSinks.put(connectionId, sink);
        userConnectionMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
        connectionIdToUserIdMap.put(connectionId, userId);
        return sink.asFlux()
                .doOnCancel(() -> removeEventStream(userId, connectionId))
                .doOnError(throwable -> removeEventStream(userId, connectionId))
                .doOnTerminate(() -> removeEventStream(userId, connectionId));
    }

    /**
     * Persists the user's connection to the distributed store and registers it in the cache.
     *
     * @param userId       The user's ID.
     * @param connectionId The connection's unique ID.
     */
    public void registerConnection(String userId, String connectionId) {
        String podName = appProperties.getPod().getId();
        String clusterName = appProperties.getClusterName();
        String globalPodId = clusterName + "-" + podName;

        connectionManager.registerConnection(userId, connectionId, globalPodId);
        cacheService.registerUserConnection(userId, connectionId, globalPodId);
        log.info("User connection saved for user: {}, connection: {}, globalPodId: {}", userId, connectionId, globalPodId);
    }

    /**
     * Removes a user's connection from in-memory maps and updates its status
     * in the distributed store and cache to INACTIVE.
     *
     * @param userId       The user's ID.
     * @param connectionId The connection's unique ID.
     */
    public void removeEventStream(String userId, String connectionId) {
        Sinks.Many<ServerSentEvent<String>> sink = userSinks.remove(connectionId);
        if (sink != null) {
            sink.tryEmitComplete();
            Set<String> connections = userConnectionMap.get(userId);
            if (connections != null) {
                connections.remove(connectionId);
                if (connections.isEmpty()) {
                    userConnectionMap.remove(userId);
                }
            }
            connectionIdToUserIdMap.remove(connectionId);
            connectionManager.removeConnection(userId, connectionId, appProperties.getPod().getId());
            cacheService.unregisterUserConnection(userId, connectionId);
            log.info("Cleanly disconnected connection {} for user {}", connectionId, userId);
        }
    }

    /**
     * Scheduled job to find and mark stale connections as INACTIVE across the cluster.
     * It also cleans up any corresponding in-memory sinks on the current pod.
     */
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "cleanupStaleSseConnections", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void cleanupStaleConnections() {
        try {
            long staleThresholdSeconds = (appProperties.getSse().getHeartbeatInterval() / 1000) * 3;
            long thresholdTimestamp = ZonedDateTime.now().minusSeconds(staleThresholdSeconds).toEpochSecond();
            Set<String> staleConnectionIds = connectionManager.getStaleConnectionIds(thresholdTimestamp);
            if (staleConnectionIds.isEmpty()) return;
            log.warn("Found {} total stale connections cluster-wide to clean up.", staleConnectionIds.size());
            for (String connectionId : staleConnectionIds) {
                connectionManager.getConnectionDetails(connectionId).ifPresent(details -> {
                    if (appProperties.getPod().getId().equals(details.getPodId())) {
                        removeEventStream(details.getUserId(), connectionId);
                    } else {
                        cacheService.unregisterUserConnection(details.getUserId(), connectionId);
                    }
                });
            }
            connectionManager.removeConnections(staleConnectionIds);
        } catch (Exception e) {
            log.error("Error during stale connection cleanup task: {}", e.getMessage(), e);
        }
    }

    private void startServerHeartbeat() {
        serverHeartbeatSubscription = Flux.interval(Duration.ofMillis(appProperties.getSse().getHeartbeatInterval()), Schedulers.parallel())
            .doOnNext(tick -> {
                try {
                    Set<String> connectionIdsOnThisPod = userSinks.keySet();
                    if (connectionIdsOnThisPod.isEmpty()) return;

                    connectionManager.updateHeartbeats(appProperties.getPod().getId(), connectionIdsOnThisPod);

                    String payload = objectMapper.writeValueAsString(Map.of("timestamp", ZonedDateTime.now()));
                    ServerSentEvent<String> heartbeatEvent = ServerSentEvent.<String>builder()
                        .event("HEARTBEAT").data(payload).build();

                    for (String connectionId : connectionIdsOnThisPod) {
                        String userId = connectionIdToUserIdMap.get(connectionId);
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
     * Pushes an event to all active connections for a given user.
     * If emitting an event fails, it triggers an asynchronous cleanup of the failed connection.
     *
     * @param userId The ID of the user to send the event to.
     * @param event  The ServerSentEvent to send.
     */
    public void sendEvent(String userId, ServerSentEvent<String> event) {
        Set<String> connectionIds = userConnectionMap.get(userId);
        if (connectionIds != null && !connectionIds.isEmpty()) {
            for (String connectionId : connectionIds) {
                Sinks.Many<ServerSentEvent<String>> sink = userSinks.get(connectionId);
                if (sink != null) {
                    Sinks.EmitResult result = sink.tryEmitNext(event);
                    if (result.isFailure()) {
                        log.warn("Failed to emit SSE event for user {}, connection {}. Result: {}. Proactively cleaning up stale connection.", userId, connectionId, result);
                        cleanupFailedConnectionAsync(userId, connectionId);
                    }
                }
            }
        }
    }

    private void cleanupFailedConnectionAsync(String userId, String connectionId) {
        Schedulers.boundedElastic().schedule(() -> removeEventStream(userId, connectionId));
    }

    public int getConnectedUserCount() {
        return userSinks.size();
    }

    public boolean isUserConnected(String userId) {
        Set<String> connections = userConnectionMap.get(userId);
        return connections != null && !connections.isEmpty();
    }

    /**
     * Retrieves the first available connection ID for a given user.
     * This is used for server-initiated actions like force logoff.
     * @param userId The user's ID.
     * @return An active connection ID for the user, or null if none is found.
     */
    public String getConnectionIdForUser(String userId) {
        Set<String> connections = userConnectionMap.get(userId);
        if (connections != null && !connections.isEmpty()) {
            return connections.iterator().next();
        }
        return null;
    }
}