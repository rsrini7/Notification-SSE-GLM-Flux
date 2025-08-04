package com.example.broadcast.controller;

import com.example.broadcast.model.UserSession;
import com.example.broadcast.repository.UserSessionRepository;
import com.example.broadcast.service.CacheService;
import com.example.broadcast.service.SseService;
import com.example.broadcast.service.BroadcastService;
import com.example.broadcast.service.UserMessageService;
import com.example.broadcast.util.Constants.BroadcastStatus;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.time.ZonedDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/sse")
@RequiredArgsConstructor
@Slf4j
public class SseController {

    private final SseService sseService;
    private final UserSessionRepository userSessionRepository;
    private final UserMessageService userMessageService;
    private final BroadcastService broadcastService;
    private final CacheService cacheService;
    
    @Value("${broadcast.pod.id:pod-local}")
    private String podId;

    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RateLimiter(name = "sseConnectLimiter", fallbackMethod = "connectFallback")
    public Flux<ServerSentEvent<String>> connect(
            @RequestParam String userId,
            @RequestParam(required = false) String sessionId,
            ServerWebExchange exchange) {
        
        log.info("SSE connection request from user: {}, session: {}, IP: {}", 
                 userId, sessionId, exchange.getRequest().getRemoteAddress() != null ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown");
        
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        
        UserSession session = UserSession.builder()
                .userId(userId)
                .sessionId(sessionId)
                .podId(podId)
                .connectionStatus(BroadcastStatus.ACTIVE.name())
                .connectedAt(ZonedDateTime.now())
                .lastHeartbeat(ZonedDateTime.now())
                .build();
        
        userSessionRepository.save(session);
        cacheService.registerUserConnection(userId, sessionId, podId);
        
        Flux<ServerSentEvent<String>> eventStream = sseService.createEventStream(userId, sessionId);
        log.info("SSE connection established for user: {}, session: {}", userId, sessionId);
        return eventStream;
    }
    
    public Flux<ServerSentEvent<String>> connectFallback(String userId, String sessionId, ServerWebExchange exchange, RequestNotPermitted ex) {
        log.warn("Connection rate limit exceeded for user: {}. IP: {}. Details: {}", 
            userId, 
            exchange.getRequest().getRemoteAddress(), 
            ex.getMessage());
        return Flux.error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Connection rate limit exceeded. Please try again later."));
    }

    @PostMapping("/disconnect")
    public ResponseEntity<String> disconnect(
            @RequestParam String userId,
            @RequestParam String sessionId) {
        
        log.info("Disconnect request from user: {}, session: {}", userId, sessionId);
        sseService.removeEventStream(userId, sessionId);
        return ResponseEntity.ok("Disconnected successfully");
    }

    @GetMapping("/stats")
    public ResponseEntity<java.util.Map<String, Object>> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        long totalActiveUsers = userSessionRepository.getTotalActiveUserCount();
        stats.put("totalActiveUsers", totalActiveUsers);
        long podActiveUsers = userSessionRepository.getActiveUserCountByPod(podId);
        stats.put("podActiveUsers", podActiveUsers);
        int sseConnectedUsers = sseService.getConnectedUserCount();
        stats.put("sseConnectedUsers", sseConnectedUsers);
        stats.put("podId", podId);
        stats.put("timestamp", ZonedDateTime.now());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/connected/{userId}")
    public ResponseEntity<Boolean> isUserConnected(@PathVariable String userId) {
        boolean connected = sseService.isUserConnected(userId);
        return ResponseEntity.ok(connected);
    }

    @PostMapping("/read")
    public ResponseEntity<String> markMessageAsRead(
            @RequestParam String userId,
            @RequestParam Long messageId) {
        userMessageService.markMessageAsRead(userId, messageId);
        broadcastService.markMessageAsReadAndPublishEvent(userId, messageId);
        return ResponseEntity.ok("Message marked as read");
    }
}