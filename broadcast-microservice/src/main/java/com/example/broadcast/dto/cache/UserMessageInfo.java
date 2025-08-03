package com.example.broadcast.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZonedDateTime;

@Getter
@AllArgsConstructor
public class UserMessageInfo {
    private final Long messageId;
    private final Long broadcastId;
    private final String content;
    private final String priority;
    private final ZonedDateTime createdAt;
    private final String deliveryStatus;
    private final String readStatus;
}