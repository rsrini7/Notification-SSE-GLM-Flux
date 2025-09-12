package com.example.broadcast.admin.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * DTO to represent the result of a "Redrive All" DLT operation.
 * It provides a summary of successful and failed attempts.
 */
@Data
@Builder
public class RedriveAllResult {
    private int totalMessages;
    private int successCount;
    private int failureCount;
    private List<RedriveFailureDetail> failures;
}