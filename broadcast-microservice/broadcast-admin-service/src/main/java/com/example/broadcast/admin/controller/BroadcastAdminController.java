package com.example.broadcast.admin.controller;

import com.example.broadcast.admin.dto.BroadcastRequest;
import com.example.broadcast.admin.dto.BroadcastResponse;
import com.example.broadcast.admin.dto.BroadcastStatsResponse;
import com.example.broadcast.admin.mapper.AdminBroadcastMapper;
import com.example.broadcast.admin.service.BroadcastLifecycleService;
import com.example.broadcast.admin.service.BroadcastQueryService;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.service.UserService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/broadcasts")
@RequiredArgsConstructor
@Slf4j
public class BroadcastAdminController {

    private final BroadcastLifecycleService broadcastLifecycleService;
    private final BroadcastQueryService broadcastQueryService;
    private final UserService userService;
    private final AdminBroadcastMapper adminBroadcastMapper;

    @PostMapping
    @RateLimiter(name = "createBroadcastLimiter")
    public ResponseEntity<BroadcastResponse> createBroadcast(
            @Valid @RequestBody BroadcastRequest request) {
        log.info("Received broadcast creation request from sender: {}", request.getSenderId());
        BroadcastResponse response = broadcastLifecycleService.createBroadcast(request);
        log.info("Broadcast created successfully with ID: {}", response.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BroadcastResponse> getBroadcast(@PathVariable Long id) {
        log.info("Admin retrieving broadcast with ID: {}", id);
        BroadcastResponse response = broadcastQueryService.getBroadcast(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<BroadcastResponse>> getBroadcasts(
            @RequestParam(defaultValue = "all") String filter) {
        log.info("Admin retrieving broadcasts with filter: {}", filter);
        List<BroadcastResponse> broadcasts;
        switch (filter.toLowerCase()) {
            case "active":
                broadcasts = broadcastQueryService.getActiveBroadcasts();
                break;
            case "scheduled":
                broadcasts = broadcastQueryService.getScheduledBroadcasts();
                break;
            case "all":
            default:
                broadcasts = broadcastQueryService.getAllBroadcasts();
                break;
        }
        return ResponseEntity.ok(broadcasts);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelBroadcast(@PathVariable Long id) {
        log.info("Admin cancelling broadcast with ID: {}", id);
        broadcastLifecycleService.cancelBroadcast(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<BroadcastStatsResponse> getBroadcastStats(@PathVariable Long id) {
        log.info("Admin retrieving statistics for broadcast ID: {}", id);
        BroadcastResponse response = broadcastQueryService.getBroadcast(id);
        BroadcastStatsResponse stats = adminBroadcastMapper.toBroadcastStatsResponse(response);

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{id}/deliveries")
    public ResponseEntity<List<UserBroadcastMessage>> getBroadcastDeliveries(@PathVariable Long id) {
        log.info("Admin retrieving delivery details for broadcast ID: {}", id);
        List<UserBroadcastMessage> deliveries = broadcastQueryService.getBroadcastDeliveries(id);
        return ResponseEntity.ok(deliveries);
    }

    @GetMapping("/users/all-ids")
    public ResponseEntity<List<String>> getAllUserIds() {
        log.info("Admin retrieving all unique user IDs.");
        List<String> userIds = userService.getAllUserIds();
        return ResponseEntity.ok(userIds);
    }
}