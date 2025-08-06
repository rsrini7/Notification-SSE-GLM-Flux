package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZonedDateTime;

@Getter
@AllArgsConstructor
public class UserConnectionInfo {
    private final String userId;
    private final String sessionId;
    private final String podId;
    private final ZonedDateTime connectedAt;
    private final ZonedDateTime lastActivity;
}