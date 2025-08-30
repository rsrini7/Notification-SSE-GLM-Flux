package com.example.broadcast.shared.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Entity representing user notification preferences
 * Used for filtering and customizing broadcast notifications
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_preferences")
public class UserPreferences {
    @Id
    private Long id;
    private String userId;
    private Boolean notificationEnabled;
    private Boolean emailNotifications;
    private Boolean pushNotifications;
    private List<String> preferredCategories; // JSON array of preferred categories
    private LocalTime quietHoursStart;
    private LocalTime quietHoursEnd;
    private String timezone;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}