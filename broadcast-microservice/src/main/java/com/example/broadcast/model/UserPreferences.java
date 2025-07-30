package com.example.broadcast.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Entity representing user notification preferences
 * Used for filtering and customizing broadcast notifications
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserPreferences {
    private Long id;
    private String userId;
    private Boolean notificationEnabled;
    private Boolean emailNotifications;
    private Boolean pushNotifications;
    private List<String> preferredCategories; // JSON array of preferred categories
    private LocalTime quietHoursStart;
    private LocalTime quietHoursEnd;
    private String timezone;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}