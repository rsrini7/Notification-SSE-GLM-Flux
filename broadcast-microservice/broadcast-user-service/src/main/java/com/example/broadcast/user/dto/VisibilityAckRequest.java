package com.example.broadcast.user.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisibilityAckRequest {
    @NotNull
    private String userId;
    @NotNull
    private Long broadcastId;
    @NotNull
    private String correlationId;
    @Nullable
    private String traceparent;
}