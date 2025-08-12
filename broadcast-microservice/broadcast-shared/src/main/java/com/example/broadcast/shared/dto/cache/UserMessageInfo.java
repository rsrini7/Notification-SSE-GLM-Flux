package com.example.broadcast.shared.dto.cache;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.time.ZonedDateTime;

@Getter
@AllArgsConstructor
public class UserMessageInfo {
    // ID of the user_broadcast_messages record
    private final Long messageId; 

    // Foreign key to the broadcast_messages record
    private final Long broadcastId;

    // User-specific status fields
    private final String deliveryStatus;
    private final String readStatus;
    private final ZonedDateTime createdAt;
}