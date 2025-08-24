package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.Serializable;
import java.time.ZonedDateTime;

@Getter
@AllArgsConstructor
public class PendingEventInfo implements Serializable{
    private final String eventId;
    private final Long broadcastId;
    private final String eventType;
    private final ZonedDateTime timestamp;
    private final String message;
}