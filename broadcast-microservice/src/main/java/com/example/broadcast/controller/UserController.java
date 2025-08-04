package com.example.broadcast.controller;

import com.example.broadcast.dto.UserBroadcastResponse;
import com.example.broadcast.dto.MessageReadRequest;
// START OF FIX: Remove unused import
// import com.example.broadcast.service.BroadcastService;
// END OF FIX
import com.example.broadcast.service.UserMessageService;
import com.example.broadcast.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserMessageService userMessageService;
    // START OF FIX: Change BroadcastService to UserService for fetching all user IDs
    private final UserService userService;
    // END OF FIX

    @GetMapping("/messages")
    public ResponseEntity<List<UserBroadcastResponse>> getUserMessages(@RequestParam String userId) {
        log.info("Retrieving messages for user: {}", userId);
        List<UserBroadcastResponse> messages = userMessageService.getUserMessages(userId);
        log.info("Retrieved {} messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/messages/unread")
    public ResponseEntity<List<UserBroadcastResponse>> getUnreadMessages(@RequestParam String userId) {
        log.info("Retrieving unread messages for user: {}", userId);
        List<UserBroadcastResponse> messages = userMessageService.getUnreadMessages(userId);
        log.info("Retrieved {} unread messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/messages/read")
    public ResponseEntity<Void> markMessageAsRead(@Valid @RequestBody MessageReadRequest request) {
        log.info("Marking message as read: user={}, message={}", request.getUserId(), request.getMessageId());
        // START OF FIX: Make a single, atomic call to the correct service.
        userMessageService.markMessageAsRead(request.getUserId(), request.getMessageId());
        // END OF FIX
        return ResponseEntity.ok().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<java.util.Map<String, Object>> getUserStats(@RequestParam String userId) {
        log.info("Getting message statistics for user: {}", userId);
        List<UserBroadcastResponse> allMessages = userMessageService.getUserMessages(userId);
        List<UserBroadcastResponse> unreadMessages = userMessageService.getUnreadMessages(userId);
        
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("userId", userId);
        stats.put("totalMessages", allMessages.size());
        stats.put("unreadMessages", unreadMessages.size());
        stats.put("readMessages", allMessages.size() - unreadMessages.size());
        stats.put("readRate", allMessages.size() > 0 ? (double) (allMessages.size() - unreadMessages.size()) / allMessages.size() : 0.0);
        return ResponseEntity.ok(stats);
    }
    
    @GetMapping("/all")
    public ResponseEntity<List<String>> getAllUserIds() {
        log.info("Retrieving all unique user IDs.");
        // START OF FIX: Use the correct service
        List<String> userIds = userService.getAllUserIds();
        // END OF FIX
        return ResponseEntity.ok(userIds);
    }
}