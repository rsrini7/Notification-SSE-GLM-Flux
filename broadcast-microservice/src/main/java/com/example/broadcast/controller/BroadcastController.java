package com.example.broadcast.controller;

import com.example.broadcast.dto.BroadcastRequest;
import com.example.broadcast.dto.BroadcastResponse;
import com.example.broadcast.dto.ErrorResponse;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.service.BroadcastService;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter; 
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/broadcasts")
@RequiredArgsConstructor
@Slf4j
public class BroadcastController {

    private final BroadcastService broadcastService;

    @PostMapping
    @RateLimiter(name = "createBroadcastLimiter", fallbackMethod = "createBroadcastFallback")
    public ResponseEntity<BroadcastResponse> createBroadcast(
            @Valid @RequestBody BroadcastRequest request) {
        log.info("Received broadcast creation request from sender: {}", request.getSenderId());
        BroadcastResponse response = broadcastService.createBroadcast(request);
        log.info("Broadcast created successfully with ID: {}", response.getId());
        return ResponseEntity.ok(response);
    }

     // MODIFIED: Added a fallback method to handle rate-limiting exceptions
    public ResponseEntity<?> createBroadcastFallback(BroadcastRequest request, RequestNotPermitted ex) {
        log.warn("Rate limit exceeded for createBroadcast by sender: {}. Details: {}", request.getSenderId(), ex.getMessage());
        ErrorResponse errorResponse = new ErrorResponse(
                ZonedDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                "You have exceeded the request limit. Please try again later.",
                "/api/broadcasts"
        );

        // Return a standard 429 Too Many Requests response
        return new ResponseEntity<>(errorResponse, HttpStatus.TOO_MANY_REQUESTS);
    }

    @GetMapping("/{id}")
    public ResponseEntity<BroadcastResponse> getBroadcast(@PathVariable Long id) {
        log.info("Retrieving broadcast with ID: {}", id);
        BroadcastResponse response = broadcastService.getBroadcast(id);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<BroadcastResponse>> getBroadcasts(
            @RequestParam(defaultValue = "all") String filter) {
        log.info("Retrieving broadcasts with filter: {}", filter);
        List<BroadcastResponse> broadcasts;
        switch (filter.toLowerCase()) {
            case "active":
                broadcasts = broadcastService.getActiveBroadcasts();
                break;
            case "scheduled":
                broadcasts = broadcastService.getScheduledBroadcasts();
                break;
            case "all":
            default:
                broadcasts = broadcastService.getAllBroadcasts();
                break;
        }
        return ResponseEntity.ok(broadcasts);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelBroadcast(@PathVariable Long id) {
        log.info("Cancelling broadcast with ID: {}", id);
        broadcastService.cancelBroadcast(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/stats")
    public ResponseEntity<Map<String, Object>> getBroadcastStats(@PathVariable Long id) {
        log.info("Retrieving statistics for broadcast ID: {}", id);
        BroadcastResponse response = broadcastService.getBroadcast(id);
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

    /**
     * **NEW:** Endpoint to get the delivery details for a specific broadcast.
     * @param id The ID of the broadcast.
     * @return A list of delivery statuses for each targeted user.
     */
    @GetMapping("/{id}/deliveries")
    public ResponseEntity<List<UserBroadcastMessage>> getBroadcastDeliveries(@PathVariable Long id) {
        log.info("Retrieving delivery details for broadcast ID: {}", id);
        List<UserBroadcastMessage> deliveries = broadcastService.getBroadcastDeliveries(id);
        return ResponseEntity.ok(deliveries);
    }
}
