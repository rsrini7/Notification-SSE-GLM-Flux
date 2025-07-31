package com.example.broadcast.controller;

import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.dto.MessageReadRequest;
import com.example.broadcast.service.BroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * REST Controller for user-specific operations
 * Provides endpoints for users to retrieve their messages and manage read status
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final BroadcastService broadcastService;

    /**
     * Get user messages
     * GET /api/user/messages?userId={userId}
     */
    @GetMapping("/messages")
    public ResponseEntity<List<UserBroadcastResponse>> getUserMessages(
            @RequestParam String userId) {
        log.info("Retrieving messages for user: {}", userId);
        
        List<UserBroadcastResponse> messages = broadcastService.getUserMessages(userId);
        
        log.info("Retrieved {} messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }

    /**
     * Get unread messages for user
     * GET /api/user/messages/unread?userId={userId}
     */
    @GetMapping("/messages/unread")
    public ResponseEntity<List<UserBroadcastResponse>> getUnreadMessages(
            @RequestParam String userId) {
        log.info("Retrieving unread messages for user: {}", userId);
        
        List<UserBroadcastResponse> messages = broadcastService.getUnreadMessages(userId);
        
        log.info("Retrieved {} unread messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }

    /**
     * Mark message as read
     * POST /api/user/messages/read
     */
    @PostMapping("/messages/read")
    public ResponseEntity<Void> markMessageAsRead(
            @Valid @RequestBody MessageReadRequest request) {
        log.info("Marking message as read: user={}, message={}", 
                request.getUserId(), request.getMessageId());
        
        broadcastService.markMessageAsRead(request.getUserId(), request.getMessageId());
        
        return ResponseEntity.ok().build();
    }

    /**
     * Get user message statistics
     * GET /api/user/stats?userId={userId}
     */
    @GetMapping("/stats")
    public ResponseEntity<java.util.Map<String, Object>> getUserStats(
            @RequestParam String userId) {
        log.info("Getting message statistics for user: {}", userId);
        
        List<UserBroadcastResponse> allMessages = broadcastService.getUserMessages(userId);
        List<UserBroadcastResponse> unreadMessages = broadcastService.getUnreadMessages(userId);
        
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("userId", userId);
        stats.put("totalMessages", allMessages.size());
        stats.put("unreadMessages", unreadMessages.size());
        stats.put("readMessages", allMessages.size() - unreadMessages.size());
        stats.put("readRate", allMessages.size() > 0 ? 
                (double) (allMessages.size() - unreadMessages.size()) / allMessages.size() : 0.0);
        
        return ResponseEntity.ok(stats);
    }
    
    /**
     * **NEW:** Get all unique user IDs
     * GET /api/user/all
     */
    @GetMapping("/all")
    public ResponseEntity<List<String>> getAllUserIds() {
        log.info("Retrieving all unique user IDs.");
        List<String> userIds = broadcastService.getAllUserIds();
        return ResponseEntity.ok(userIds);
    }
}
