package com.example.broadcast.shared.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity representing broadcast statistics
 * Tracks performance metrics and delivery statistics for broadcasts
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("broadcast_statistics")
public class BroadcastStatistics{
    @Id
    private Long id;
    private Long broadcastId;
    private Integer totalTargeted;
    private Integer totalDelivered;
    private Integer totalRead;
    private Integer totalFailed;
    private Long avgDeliveryTimeMs;
    private OffsetDateTime calculatedAt;
}