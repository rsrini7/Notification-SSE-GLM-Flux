package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.time.ZonedDateTime;

@Getter
@ToString
@AllArgsConstructor
public class UserConnectionInfo {
    private final String userId;
    private final String connectionId;
    private final String podId;
    private final ZonedDateTime connectedAt;
    private final ZonedDateTime lastActivityAt;
}