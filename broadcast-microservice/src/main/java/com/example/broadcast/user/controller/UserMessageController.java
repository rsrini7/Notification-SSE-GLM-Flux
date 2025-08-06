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
@RequestMapping("/api/user/messages") // MODIFIED: Route is more specific
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

    @GetMapping("/unread")
    public ResponseEntity<List<UserBroadcastResponse>> getUnreadMessages(@RequestParam String userId) {
        log.info("Retrieving unread messages for user: {}", userId);
        List<UserBroadcastResponse> messages = userMessageService.getUnreadMessages(userId);
        log.info("Retrieved {} unread messages for user: {}", messages.size(), userId);
        return ResponseEntity.ok(messages);
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markMessageAsRead(@Valid @RequestBody MessageReadRequest request) {
        log.info("Marking message as read: user={}, message={}", request.getUserId(), request.getMessageId());
        userMessageService.markMessageAsRead(request.getUserId(), request.getMessageId());
        return ResponseEntity.ok().build();
    }
    
    // REMOVED: The /all endpoint was moved to BroadcastAdminController
}