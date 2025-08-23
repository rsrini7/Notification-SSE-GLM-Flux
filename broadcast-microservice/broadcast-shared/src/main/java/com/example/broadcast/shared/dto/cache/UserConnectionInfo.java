package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;
import java.time.ZonedDateTime;

@Getter
@ToString
@AllArgsConstructor
public class UserConnectionInfo implements Serializable{
    private final String userId;
    private final String connectionId;
    private final String podName;
    private final String clusterName;
    private final ZonedDateTime connectedAt;
    private final ZonedDateTime lastActivityAt;
}