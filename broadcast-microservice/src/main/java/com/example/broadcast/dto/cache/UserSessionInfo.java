package com.example.broadcast.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZonedDateTime;

@Getter
@AllArgsConstructor
public class UserSessionInfo {
    private final String userId;
    private final String sessionId;
    private final String podId;
    private final ZonedDateTime lastHeartbeat;
}