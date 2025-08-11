package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZonedDateTime;

// MERGED: This class now combines fields from the old UserSessionInfo
@Getter
@AllArgsConstructor
public class UserConnectionInfo {
    private final String userId;
    // CHANGED: Renamed from sessionId to connectionId
    private final String connectionId;
    private final String podId;
    // MERGED: Added from the old UserConnectionInfo
    private final ZonedDateTime connectedAt;
    // MERGED: Renamed from lastHeartbeat/lastActivity to a single, clearer field
    private final ZonedDateTime lastActivityAt;
}