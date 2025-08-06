package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZonedDateTime;

@Getter
@AllArgsConstructor
public class BroadcastStatsInfo {
    private final Long broadcastId;
    private final Integer totalTargeted;
    private final Integer totalDelivered;
    private final Integer totalRead;
    private final ZonedDateTime calculatedAt;
}