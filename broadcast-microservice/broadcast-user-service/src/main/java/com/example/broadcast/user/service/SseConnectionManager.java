package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.user.service.cache.CacheService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.CacheClosedException;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
@DependsOn("geodeClientCache")
@Profile("!checkpoint-build")
public class SseConnectionManager {

    private final Map<String, Sinks.Many<ServerSentEvent<String>>> connectionSinks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userToConnectionIdsMap = new ConcurrentHashMap<>();
    private final Map<String, String> connectionIdToUserIdMap = new ConcurrentHashMap<>();

    private final CacheService cacheService;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final ClientCache clientCache; // Inject ClientCache to check its status

    private Disposable serverHeartbeatSubscription;

    @PostConstruct
    public void init() {
        startServerHeartbeat();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Commencing SseConnectionManager graceful shutdown...");
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
        
        try {
            String connectedPayload = objectMapper.writeValueAsString(Map.of(
                "message", "SSE connection established",
                "connectionId", connectionId,
                "timestamp", ZonedDateTime.now().toString()
            ));
            ServerSentEvent<String> connectedEvent = ServerSentEvent.<String>builder()
                .event(Constants.SseEventType.CONNECTED.name())
                .data(connectedPayload)
                .build();
            sink.tryEmitNext(connectedEvent);
            log.debug("Sent initial CONNECTED event for connection {}", connectionId);

        } catch (JsonProcessingException e) {
            log.error("Error creating CONNECTED event payload for connection {}", connectionId, e);
        }
        
        return sink.asFlux()
                .doOnCancel(() -> removeEventStream(userId, connectionId))
                .doOnError(throwable -> removeEventStream(userId, connectionId))
                .doOnTerminate(() -> removeEventStream(userId, connectionId));
    }

    public void registerConnection(String userId, String connectionId) {
        String podName = appProperties.getPodName();
        String clusterName = appProperties.getClusterName();
        cacheService.registerUserConnection(userId, connectionId, podName, clusterName);
        log.info("[REGISTER] Connection registered for userId='{}', connection='{}', cluster='{}', pod='{}'", userId, connectionId, clusterName, podName);
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
            
            // **THIS IS THE FIX**: Check if the cache is closed before trying to use it.
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
                    // Check if cache is closed before running heartbeat logic
                    if (clientCache.isClosed()) {
                        log.warn("Cache is closed, skipping heartbeat.");
                        return;
                    }

                    Set<String> connectionIdsOnThisPod = connectionSinks.keySet();
                    if (connectionIdsOnThisPod.isEmpty()) return;
  
                    cacheService.updateHeartbeats(connectionIdsOnThisPod);
                    String payload = objectMapper.writeValueAsString(Map.of("timestamp", ZonedDateTime.now()));
                    ServerSentEvent<String> heartbeatEvent = ServerSentEvent.<String>builder()
                        .event("HEARTBEAT").data(payload).build();
           
                    for (String connectionId : connectionIdsOnThisPod) {
                        Sinks.Many<ServerSentEvent<String>> sink = connectionSinks.get(connectionId);
                        if (sink != null) {
                            sink.tryEmitNext(heartbeatEvent);
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
        return connectionSinks.size();
    }

    public boolean isUserConnected(String userId) {
        Set<String> connections = userToConnectionIdsMap.get(userId);
        return connections != null && !connections.isEmpty();
    }
}