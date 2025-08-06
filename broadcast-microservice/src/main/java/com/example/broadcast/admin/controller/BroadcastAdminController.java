package com.example.broadcast.admin.controller;

import com.example.broadcast.admin.dto.BroadcastRequest;
import com.example.broadcast.admin.dto.BroadcastResponse;
import com.example.broadcast.admin.service.BroadcastCreationService;
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
import java.util.Map;

@RestController
@RequestMapping("/api/admin/broadcasts") // MODIFIED: Route is now namespaced under /admin
@RequiredArgsConstructor
@Slf4j
public class BroadcastAdminController {

    private final BroadcastCreationService broadcastCreationService;
    private final BroadcastQueryService broadcastQueryService;
    private final BroadcastLifecycleService broadcastLifecycleService;
    private final UserService userService; // Added for the getAllUserIds endpoint

    @PostMapping
    @RateLimiter(name = "createBroadcastLimiter")
    public ResponseEntity<BroadcastResponse> createBroadcast(
            @Valid @RequestBody BroadcastRequest request) {
        log.info("Received broadcast creation request from sender: {}", request.getSenderId());
        BroadcastResponse response = broadcastCreationService.createBroadcast(request);
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
    public ResponseEntity<Map<String, Object>> getBroadcastStats(@PathVariable Long id) {
        log.info("Admin retrieving statistics for broadcast ID: {}", id);
        BroadcastResponse response = broadcastQueryService.getBroadcast(id);
        Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("broadcastId", id);
        stats.put("totalTargeted", response.getTotalTargeted());
        stats.put("totalDelivered", response.getTotalDelivered());
        stats.put("totalRead", response.getTotalRead());
        stats.put("deliveryRate", response.getTotalTargeted() > 0 ?
                (double) response.getTotalDelivered() / response.getTotalTargeted() : 0.0);
        stats.put("readRate", response.getTotalDelivered() > 0 ?
                (double) response.getTotalRead() / response.getTotalDelivered() : 0.0);
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/{id}/deliveries")
    public ResponseEntity<List<UserBroadcastMessage>> getBroadcastDeliveries(@PathVariable Long id) {
        log.info("Admin retrieving delivery details for broadcast ID: {}", id);
        List<UserBroadcastMessage> deliveries = broadcastQueryService.getBroadcastDeliveries(id);
        return ResponseEntity.ok(deliveries);
    }

    // NEW: Moved from UserMessageController as this is an admin-level function
    @GetMapping("/users/all-ids")
    public ResponseEntity<List<String>> getAllUserIds() {
        log.info("Admin retrieving all unique user IDs.");
        List<String> userIds = userService.getAllUserIds();
        return ResponseEntity.ok(userIds);
    }
}