package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

@Getter
@ToString
@AllArgsConstructor
public class UserConnectionInfo implements Serializable{
    private final String userId;
    private final String connectionId;
    private final String podName;
    private final String clusterName;
    private final long connectedAtEpochMilli;
    private final long lastActivityAtEpochMilli;
}