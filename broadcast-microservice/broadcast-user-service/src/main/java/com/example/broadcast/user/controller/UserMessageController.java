package com.example.broadcast.user.controller;

import com.example.broadcast.user.dto.MessageReadRequest;
import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.user.service.UserMessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/user/messages")
@RequiredArgsConstructor
@Slf4j
public class UserMessageController {

    private final UserMessageService userMessageService;

    @GetMapping
    public Mono<ResponseEntity<List<UserBroadcastResponse>>> getUserMessages(@RequestParam String userId) {
        log.info("Retrieving messages for user: {}", userId);
        return userMessageService.getUserMessages(userId)
                .map(messages -> {
                    log.info("Retrieved {} messages for user: {}", messages.size(), userId);
                    return ResponseEntity.ok(messages);
                });
    }

    @PostMapping("/read")
    public ResponseEntity<Void> markMessageAsRead(@Valid @RequestBody MessageReadRequest request) {
        log.info("Marking message as read: user={}, broadcast={}", request.getUserId(), request.getBroadcastId());
        userMessageService.markMessageAsRead(request.getUserId(), request.getBroadcastId());
        return ResponseEntity.ok().build();
    }
}