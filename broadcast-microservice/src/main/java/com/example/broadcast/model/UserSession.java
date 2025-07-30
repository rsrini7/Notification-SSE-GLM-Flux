package com.example.broadcast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity representing user session and connection tracking
 * Used for efficient SSE routing and connection management
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSession {
    private Long id;
    private String userId;
    private String sessionId;
    private String podId;
    private String connectionStatus; // ACTIVE, INACTIVE, EXPIRED
    private LocalDateTime connectedAt;
    private LocalDateTime disconnectedAt;
    private LocalDateTime lastHeartbeat;
}