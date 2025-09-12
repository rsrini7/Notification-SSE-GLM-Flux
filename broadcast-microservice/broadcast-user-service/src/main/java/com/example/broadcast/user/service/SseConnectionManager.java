package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.user.service.cache.CacheService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.CacheClosedException;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
@DependsOn("geodeClientCache")
public class SseConnectionManager {

    private final Map<String, Sinks.Many<ServerSentEvent<String>>> connectionSinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userToConnectionIdsMap = new ConcurrentHashMap<>();
    private final Map<String, String> connectionIdToUserIdMap = new ConcurrentHashMap<>();
    private final Map<String, Integer> failedEmitCounts = new ConcurrentHashMap<>();

    private final CacheService cacheService;
    private final AppProperties appProperties;
    private final ClientCache clientCache;
    private final SseEventFactory sseEventFactory;

    private Disposable serverHeartbeatSubscription;

    @PostConstruct
    public void init() {
        startServerHeartbeat();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Commencing SseConnectionManager graceful shutdown...");

        // Send graceful shutdown notice to all connected clients
        if (!connectionSinks.isEmpty()) {
            try {
                log.info("Sending graceful shutdown notice to {} connected clients...", connectionSinks.size());
                ServerSentEvent<String> shutdownEvent = sseEventFactory.createShutdownEvent();
                connectionSinks.forEach((connectionId, sink) -> sink.tryEmitNext(shutdownEvent));

                // Brief delay to allow message delivery
                Thread.sleep(500);
                log.info("Shutdown notice sent to clients.");
            } catch (Exception e) {
                log.warn("Error sending shutdown notice to clients", e);
            }
        }

        if (serverHeartbeatSubscription != null && !serverHeartbeatSubscription.isDisposed()) {
            serverHeartbeatSubscription.dispose();
            log.info("Server heartbeat task stopped.");
        }
        if (!connectionIdToUserIdMap.isEmpty()) {
            log.info("Unregistering {} active user connections from Geode...", connectionIdToUserIdMap.size());
            new ArrayList<>(connectionIdToUserIdMap.keySet()).forEach(connectionId -> {
                String userId = connectionIdToUserIdMap.get(connectionId);
                if (userId != null) {
                    removeEventStream(userId, connectionId);
                }
            });
        }
        log.info("SseConnectionManager cleanup complete.");
    }

    public Flux<ServerSentEvent<String>> createEventStream(String userId, String connectionId) {
        log.debug("Creating SSE event stream for user: {}, connection: {}", userId, connectionId);
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();

        connectionSinks.put(connectionId, sink);
        userToConnectionIdsMap.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(connectionId);
        connectionIdToUserIdMap.put(connectionId, userId);

        ServerSentEvent<String> connectedEvent = sseEventFactory.createConnectedEvent(connectionId);
        if (connectedEvent != null) {
            sink.tryEmitNext(connectedEvent);
            log.debug("Sent initial CONNECTED event for connection {}", connectionId);
        }

        return sink.asFlux()
                .doOnCancel(() -> removeEventStream(userId, connectionId))
                .doOnError(throwable -> removeEventStream(userId, connectionId))
                .doOnTerminate(() -> removeEventStream(userId, connectionId));
    }

    public void removeEventStream(String userId, String connectionId) {
        Sinks.Many<ServerSentEvent<String>> sink = connectionSinks.remove(connectionId);
        if (sink != null) {
            sink.tryEmitComplete();
            Set<String> connections = userToConnectionIdsMap.get(userId);
            if (connections != null) {
                connections.remove(connectionId);
                if (connections.isEmpty()) {
                    userToConnectionIdsMap.remove(userId);
                }
            }
            connectionIdToUserIdMap.remove(connectionId);

            if (clientCache.isClosed()) {
                log.warn("Cache is closed. Skipping Geode unregister for connection {} on shutdown.", connectionId);
            } else {
                cacheService.unregisterUserConnection(userId, connectionId);
            }

            log.info("Cleanly disconnected connection {} for user {}", connectionId, userId);
        }
    }

    private void startServerHeartbeat() {
        serverHeartbeatSubscription = Flux.interval(Duration.ofMillis(appProperties.getSse().getHeartbeatInterval()), Schedulers.parallel())
            .doOnNext(tick -> {
                try {
                    if (clientCache.isClosed()) {
                        log.warn("Cache is closed, skipping heartbeat.");
                        return;
                    }

                    Set<String> connectionIdsOnThisPod = connectionSinks.keySet();
                    if (connectionIdsOnThisPod.isEmpty()) return;

                    cacheService.updateHeartbeats(connectionIdsOnThisPod);

                    /*// Detect and cleanup stale connections
                    // NOTE: Assumes `getClientTimeoutThreshold()` exists in AppProperties.Sse
                    long now = OffsetDateTime.now().toEpochSecond();
                    long staleThreshold = now - (appProperties.getSse().getClientTimeoutThreshold() / 1000);
                    Set<String> staleConnections = cacheService.getStaleConnectionIds(staleThreshold);

                    // Only check connections on this pod
                    staleConnections.retainAll(connectionIdsOnThisPod);

                    if (!staleConnections.isEmpty()) {
                        log.warn("Detected {} stale connections. Cleaning up.", staleConnections.size());
                        for (String staleConnectionId : staleConnections) {
                            String userId = connectionIdToUserIdMap.get(staleConnectionId);
                            if (userId != null) {
                                log.warn("Connection {} for user {} appears stale - no activity for more than {}ms",
                                    staleConnectionId, userId, appProperties.getSse().getClientTimeoutThreshold());
                                cleanupFailedConnectionAsync(userId, staleConnectionId);
                            }
                        }
                    }*/

                    // Send heartbeat to active connections
                    ServerSentEvent<String> heartbeatEvent = sseEventFactory.createHeartbeatEvent();

                    if (heartbeatEvent != null) {
                        for (String connectionId : connectionIdsOnThisPod) {
                            Sinks.Many<ServerSentEvent<String>> sink = connectionSinks.get(connectionId);
                            if (sink != null) {
                                sink.tryEmitNext(heartbeatEvent);
                            }
                        }
                    }
                } catch (CacheClosedException e) {
                    log.warn("Cache closed during heartbeat task. Suppressing error.");
                } catch (Exception e) {
                    log.error("Error in server heartbeat task: {}", e.getMessage());
                }
            })
            .subscribe();
    }

    public void sendEvent(String userId, ServerSentEvent<String> event) {
        Set<String> connectionIds = userToConnectionIdsMap.get(userId);
        if (connectionIds != null && !connectionIds.isEmpty()) {
            for (String connectionId : Set.copyOf(connectionIds)) {
                Sinks.Many<ServerSentEvent<String>> sink = connectionSinks.get(connectionId);
                if (sink != null) {
                    Sinks.EmitResult result = sink.tryEmitNext(event);
                    if (result.isFailure()) {
                        // Increment failure counter
                        int failCount = failedEmitCounts.compute(connectionId, (k, v) -> v == null ? 1 : v + 1);

                        log.warn("Failed to emit SSE event for user {}, connection {}. Result: {}. Fail count: {}",
                            userId, connectionId, result, failCount);

                        // If multiple failures in a row, clean up connection
                        if (failCount >= 3) {
                            log.warn("Connection {} has failed {} consecutive emits. Proactively cleaning up stale connection.",
                                connectionId, failCount);
                            cleanupFailedConnectionAsync(userId, connectionId);
                        }
                    } else {
                        // Reset counter on success
                        failedEmitCounts.remove(connectionId);
                    }
                }
            }
        }
    }

    private void cleanupFailedConnectionAsync(String userId, String connectionId) {
        Schedulers.boundedElastic().schedule(() -> removeEventStream(userId, connectionId));
    }

    public int getConnectedUserCount() {
        return connectionSinks.size();
    }

    public boolean isUserConnected(String userId) {
        Set<String> connections = userToConnectionIdsMap.get(userId);
        return connections != null && !connections.isEmpty();
    }

    public void broadcastEventToLocalConnections(ServerSentEvent<String> event) {
        if (event == null) {
            return;
        }
        log.info("Broadcasting event to all {} local connections on this pod.", connectionSinks.size());
        // Iterate over a copy of the values to avoid concurrency issues
        for (Sinks.Many<ServerSentEvent<String>> sink : new ArrayList<>(connectionSinks.values())) {
            sink.tryEmitNext(event);
        }
    }

    public Set<String> getLocalUserIds() {
        // userToConnectionIdsMap contains all users with active connections on this pod.
        return new HashSet<>(userToConnectionIdsMap.keySet());
    }
}