// broadcast-microservice/src/main/java/com/example/broadcast/controller/SseController.java
package com.example.broadcast.controller;

import com.example.broadcast.model.UserSession;
import com.example.broadcast.repository.UserSessionRepository;
import com.example.broadcast.service.SseService;
import com.example.broadcast.service.CaffeineCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import org.springframework.web.server.ServerWebExchange;
import java.time.ZonedDateTime;
import java.util.UUID;
import com.example.broadcast.service.BroadcastService;
import org.springframework.http.HttpStatus;

import com.example.broadcast.util.Constants.BroadcastStatus;

/**
 * REST Controller for Server-Sent Events (SSE)
 * Provides real-time message delivery to connected users
 */
@RestController
@RequestMapping("/api/sse")
@RequiredArgsConstructor
@Slf4j
public class SseController {

    private final SseService sseService;
    private final UserSessionRepository userSessionRepository;
    private final BroadcastService broadcastService;
    private final CaffeineCacheService caffeineCacheService;
    
    @Value("${broadcast.pod.id:pod-local}")
    private String podId;

    /**
     * Establish SSE connection for a user
     * GET /api/sse/connect?userId={userId}
     */
    @GetMapping(value = "/connect", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> connect(
            @RequestParam String userId,
            @RequestParam(required = false) String sessionId,
            ServerWebExchange exchange) {
        
        log.info("SSE connection request from user: {}, session: {}, IP: {}", 
                userId, sessionId, exchange.getRequest().getRemoteAddress() != null ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown");
        // Generate session ID if not provided
        if (sessionId == null || sessionId.trim().isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        
        // Create or update user session
        UserSession session = UserSession.builder()
                .userId(userId)
                .sessionId(sessionId)
                .podId(podId)
                .connectionStatus(BroadcastStatus.ACTIVE.name())
                .connectedAt(ZonedDateTime.now())
                .lastHeartbeat(ZonedDateTime.now())
                .build();
        userSessionRepository.save(session);
        caffeineCacheService.registerUserConnection(userId, sessionId, podId);
        
        // Create reactive SSE connection
        Flux<String> eventStream = sseService.createEventStream(userId);
        log.info("SSE connection established for user: {}, session: {}", userId, sessionId);
        
        return eventStream;
    }

    /**
     * Handle user heartbeat to keep connection alive
     * POST /api/sse/heartbeat?userId={userId}&sessionId={sessionId}
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<String> heartbeat(
            @RequestParam String userId,
            @RequestParam String sessionId) {
        log.debug("Heartbeat received from user: {}, session: {}", userId, sessionId);
        // Update session heartbeat
        int updated = userSessionRepository.updateHeartbeat(sessionId, podId);
        if (updated > 0) {
            return ResponseEntity.ok("Heartbeat updated");
        } else {
            log.warn("Session not found for heartbeat: user={}, session={}", userId, sessionId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Disconnect SSE connection
     * POST /api/sse/disconnect?userId={userId}&sessionId={sessionId}
     */
    @PostMapping("/disconnect")
    public ResponseEntity<String> disconnect(
            @RequestParam String userId,
            @RequestParam String sessionId) {
        
        log.info("Disconnect request from user: {}, session: {}", userId, sessionId);
        // Mark session as inactive
        int updated = userSessionRepository.markSessionInactive(sessionId, podId);
        if (updated > 0) {
            caffeineCacheService.unregisterUserConnection(userId, sessionId);
            return ResponseEntity.ok("Disconnected successfully");
        } else {
            log.warn("Session not found for disconnect: user={}, session={}", userId, sessionId);
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get connection statistics
     * GET /api/sse/stats
     */
    @GetMapping("/stats")
    public ResponseEntity<java.util.Map<String, Object>> getStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        // Get total active users
        long totalActiveUsers = userSessionRepository.getTotalActiveUserCount();
        stats.put("totalActiveUsers", totalActiveUsers);
        // Get active users for this pod
        long podActiveUsers = userSessionRepository.getActiveUserCountByPod(podId);
        stats.put("podActiveUsers", podActiveUsers);
        // Get SSE connected users
        int sseConnectedUsers = sseService.getConnectedUserCount();
        stats.put("sseConnectedUsers", sseConnectedUsers);
        // Get pod information
        stats.put("podId", podId);
        stats.put("timestamp", ZonedDateTime.now());
        log.info("SSE stats: total={}, pod={}, sse={}", 
                totalActiveUsers, podActiveUsers, sseConnectedUsers);
        return ResponseEntity.ok(stats);
    }

    /**
     * Check if user is connected
     * GET /api/sse/connected/{userId}
     */
    @GetMapping("/connected/{userId}")
    public ResponseEntity<Boolean> isUserConnected(@PathVariable String userId) {
        boolean connected = sseService.isUserConnected(userId);
        return ResponseEntity.ok(connected);
    }

    /**
     * Mark message as read
     * POST /api/sse/read?userId={userId}&messageId={messageId}
     */
    @PostMapping("/read")
    public ResponseEntity<String> markMessageAsRead(
            @RequestParam String userId,
            @RequestParam Long messageId) {
        try {
            broadcastService.markMessageAsRead(userId, messageId);
        } catch (Exception e) {
            log.error("Error marking message as read: user={}, message={}", userId, messageId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error marking message as read");
        }
        
        log.info("Marking message as read: user={}, message={}", userId, messageId);
        return ResponseEntity.ok("Message marked as read");
    }
}