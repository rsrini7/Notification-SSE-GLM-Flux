package com.example.broadcast.user.controller;

import com.example.broadcast.user.dto.MessageReadRequest;
import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.user.service.UserMessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user/messages")
@RequiredArgsConstructor
@Slf4j
public class UserMessageController {

    private final UserMessageService userMessageService;

    @GetMapping
    public ResponseEntity<List<UserBroadcastResponse>> getUserMessages(@RequestParam String userId) {
        log.info("Retrieving messages for user: {}", userId);
        List<UserBroadcastResponse> messages = userMessageService.getUserMessages(userId);
        log.info("Retrieved {} messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }
    
    @GetMapping("/groups")
    public ResponseEntity<List<UserBroadcastResponse>> getGroupMessages(@RequestParam String userId) {
        log.info("Retrieving group messages for user: {}", userId);
        List<UserBroadcastResponse> messages = userMessageService.getGroupMessagesForUser(userId);
        log.info("Retrieved {} group messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }

    @GetMapping("/unread")
    public ResponseEntity<List<UserBroadcastResponse>> getUnreadMessages(@RequestParam String userId) {
        log.info("Retrieving unread messages for user: {}", userId);
        List<UserBroadcastResponse> messages = userMessageService.getUnreadMessages(userId);
        log.info("Retrieved {} unread messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markMessageAsRead(@Valid @RequestBody MessageReadRequest request) {
        // Note: Ensure MessageReadRequest DTO has a 'broadcastId' field instead of 'messageId'
        log.info("Marking message as read: user={}, broadcast={}", request.getUserId(), request.getBroadcastId());
        userMessageService.markMessageAsRead(request.getUserId(), request.getBroadcastId());
        return ResponseEntity.ok().build();
    }
}