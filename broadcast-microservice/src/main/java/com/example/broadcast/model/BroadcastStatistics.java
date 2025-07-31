package com.example.broadcast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;

/**
 * Entity representing broadcast statistics
 * Tracks performance metrics and delivery statistics for broadcasts
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastStatistics {
    private Long id;
    private Long broadcastId;
    private Integer totalTargeted;
    private Integer totalDelivered;
    private Integer totalRead;
    private Integer totalFailed;
    private Long avgDeliveryTimeMs;
    private ZonedDateTime calculatedAt;
}