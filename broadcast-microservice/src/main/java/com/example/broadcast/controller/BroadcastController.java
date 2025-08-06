package com.example.broadcast.controller;

import com.example.broadcast.dto.BroadcastRequest;
import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.service.BroadcastLifecycleService;
import com.example.broadcast.service.BroadcastQueryService;
import com.example.broadcast.service.BroadcastService;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
@Slf4j
public class BroadcastController {

    // Command service for creating broadcasts
    private final BroadcastService broadcastService;
    // Query service for retrieving broadcast data
    private final BroadcastQueryService broadcastQueryService;
    // Service for handling lifecycle changes (e.g., cancellation)
    private final BroadcastLifecycleService broadcastLifecycleService;

    @PostMapping 
    @RateLimiter(name = "createBroadcastLimiter")
    public ResponseEntity<BroadcastResponse> createBroadcast(
            @Valid @RequestBody BroadcastRequest request) {
        log.info("Received broadcast creation request from sender: {}", request.getSenderId());
        BroadcastResponse response = broadcastService.createBroadcast(request); 
        log.info("Broadcast created successfully with ID: {}", response.getId());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}") 
    public ResponseEntity<BroadcastResponse> getBroadcast(@PathVariable Long id) {
        log.info("Retrieving broadcast with ID: {}", id);
        BroadcastResponse response = broadcastQueryService.getBroadcast(id); 
        return ResponseEntity.ok(response);
    }

    @GetMapping 
    public ResponseEntity<List<BroadcastResponse>> getBroadcasts(
            @RequestParam(defaultValue = "all") String filter) {
        log.info("Retrieving broadcasts with filter: {}", filter);
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
        log.info("Cancelling broadcast with ID: {}", id);
        broadcastLifecycleService.cancelBroadcast(id); 
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/stats") 
    public ResponseEntity<Map<String, Object>> getBroadcastStats(@PathVariable Long id) {
        log.info("Retrieving statistics for broadcast ID: {}", id);
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
        log.info("Retrieving delivery details for broadcast ID: {}", id);
        List<UserBroadcastMessage> deliveries = broadcastQueryService.getBroadcastDeliveries(id);
        return ResponseEntity.ok(deliveries);
    }
}