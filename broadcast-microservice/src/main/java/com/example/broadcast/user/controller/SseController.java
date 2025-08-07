package com.example.broadcast.user.controller;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.user.service.DistributedSessionManager;
import com.example.broadcast.user.service.SseService;
import com.example.broadcast.user.service.UserMessageService;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final UserMessageService userMessageService;
    private final DistributedSessionManager sessionManager;
    private final AppProperties appProperties;

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

        sseService.registerConnection(userId, sessionId);
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
        stats.put("totalActiveUsers", sessionManager.getTotalActiveUsers());
        stats.put("podActiveUsers", sessionManager.getPodActiveUsers(appProperties.getPod().getId()));
        stats.put("sseConnectedUsers", sseService.getConnectedUserCount());
        stats.put("podId", appProperties.getPod().getId());
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
        return ResponseEntity.ok("Message marked as read");
    }
}