package com.example.broadcast.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * DTO for the event published when an administrator requests to redrive a message from the DLT.
 * This event communicates the intent from the admin domain to other domains.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageRedriveRequestedEvent {
    @Builder.Default
    private String eventId = UUID.randomUUID().toString();
    private Long userBroadcastMessageId; // The ID of the message to reset
    private ZonedDateTime requestedAt;
}