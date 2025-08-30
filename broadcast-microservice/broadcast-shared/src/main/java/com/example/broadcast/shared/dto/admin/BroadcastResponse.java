package com.example.broadcast.shared.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BroadcastResponse {
    private Long id;
    private String senderId;
    private String senderName;
    private String content;
    private String targetType;
    private List<String> targetIds;
    private String priority;
    private String category;
    private OffsetDateTime expiresAt;
    private OffsetDateTime createdAt;
    // **FIX:** Added the scheduledAt field to be sent to the frontend.
    private OffsetDateTime scheduledAt;
    private String status;
    private Integer totalTargeted;
    private Integer totalDelivered;
    private Integer totalRead;
}
