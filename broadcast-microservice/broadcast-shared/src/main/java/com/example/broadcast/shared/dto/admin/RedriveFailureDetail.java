package com.example.broadcast.shared.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO to hold details about a single failed redrive attempt within a batch operation.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class RedriveFailureDetail {
    private String dltMessageId;
    private String reason;
}