package com.example.broadcast.controller;

import com.example.broadcast.dto.BroadcastRequest;
import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.service.BroadcastService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * REST Controller for broadcast message operations
 * Provides endpoints for administrators to create and manage broadcasts
 */
@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
@Slf4j
public class BroadcastController {

    private final BroadcastService broadcastService;

    /**
     * Create a new broadcast message
     * POST /api/broadcasts
     */
    @PostMapping
    public ResponseEntity<BroadcastResponse> createBroadcast(
            @Valid @RequestBody BroadcastRequest request) {
        log.info("Received broadcast creation request from sender: {}", request.getSenderId());
        
        BroadcastResponse response = broadcastService.createBroadcast(request);
        
        log.info("Broadcast created successfully with ID: {}", response.getId());
        return ResponseEntity.ok(response);
    }

    /**
     * Get broadcast by ID
     * GET /api/broadcasts/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<BroadcastResponse> getBroadcast(@PathVariable Long id) {
        log.info("Retrieving broadcast with ID: {}", id);
        
        BroadcastResponse response = broadcastService.getBroadcast(id);
        
        return ResponseEntity.ok(response);
    }

    /**
     * Get all active broadcasts
     * GET /api/broadcasts
     */
    @GetMapping
    public ResponseEntity<List<BroadcastResponse>> getActiveBroadcasts() {
        log.info("Retrieving all active broadcasts");
        
        List<BroadcastResponse> broadcasts = broadcastService.getActiveBroadcasts();
        
        return ResponseEntity.ok(broadcasts);
    }

    /**
     * Cancel a broadcast
     * DELETE /api/broadcasts/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelBroadcast(@PathVariable Long id) {
        log.info("Cancelling broadcast with ID: {}", id);
        
        broadcastService.cancelBroadcast(id);
        
        return ResponseEntity.noContent().build();
    }

    /**
     * Get broadcast statistics
     * GET /api/broadcasts/{id}/stats
     */
    @GetMapping("/{id}/stats")
    public ResponseEntity<java.util.Map<String, Object>> getBroadcastStats(@PathVariable Long id) {
        log.info("Retrieving statistics for broadcast ID: {}", id);
        
        BroadcastResponse response = broadcastService.getBroadcast(id);
        
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
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
}