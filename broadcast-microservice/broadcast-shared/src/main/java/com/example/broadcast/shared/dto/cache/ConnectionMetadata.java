package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.With;

import java.io.Serializable;

@Getter
@AllArgsConstructor
@With // Useful for non-destructive updates
public class ConnectionMetadata implements Serializable {
    private final String userId;
    private final long lastHeartbeatTimestamp;
}