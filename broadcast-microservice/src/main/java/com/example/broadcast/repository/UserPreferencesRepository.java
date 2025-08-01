package com.example.broadcast.repository;

import com.example.broadcast.model.UserPreferences;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.time.ZoneOffset;

/**
 * Repository for user preferences operations using Spring JDBC
 */
@Repository
public class UserPreferencesRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserPreferencesRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<UserPreferences> preferencesRowMapper = new RowMapper<>() {
        @Override
        public UserPreferences mapRow(ResultSet rs, int rowNum) throws SQLException {
            return UserPreferences.builder()
                    .id(rs.getLong("id"))
                    .userId(rs.getString("user_id"))
                    .notificationEnabled(rs.getBoolean("notification_enabled"))
                    .emailNotifications(rs.getBoolean("email_notifications"))
                    .pushNotifications(rs.getBoolean("push_notifications"))
                    .preferredCategories(parseJsonArray(rs.getString("preferred_categories")))
                    .quietHoursStart(rs.getTime("quiet_hours_start") != null ? 
                            rs.getTime("quiet_hours_start").toLocalTime() : null)
                    .quietHoursEnd(rs.getTime("quiet_hours_end") != null ? 
                            rs.getTime("quiet_hours_end").toLocalTime() : null)
                    .timezone(rs.getString("timezone"))
                    .createdAt(rs.getTimestamp("created_at").toInstant().atZone(ZoneOffset.UTC))
                    .updatedAt(rs.getTimestamp("updated_at").toInstant().atZone(ZoneOffset.UTC))
                    .build();
        }
    };

    /**
     * Find user preferences by user ID
     */
    public Optional<UserPreferences> findByUserId(String userId) {
        String sql = "SELECT * FROM user_preferences WHERE user_id = ?";
        List<UserPreferences> results = jdbcTemplate.query(sql, preferencesRowMapper, userId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Save user preferences
     */
    public UserPreferences save(UserPreferences preferences) {
        String sql = """
            INSERT INTO user_preferences 
            (user_id, notification_enabled, email_notifications, push_notifications, 
             preferred_categories, quiet_hours_start, quiet_hours_end, timezone)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
            notification_enabled = VALUES(notification_enabled),
            email_notifications = VALUES(email_notifications),
            push_notifications = VALUES(push_notifications),
            preferred_categories = VALUES(preferred_categories),
            quiet_hours_start = VALUES(quiet_hours_start),
            quiet_hours_end = VALUES(quiet_hours_end),
            timezone = VALUES(timezone),
            updated_at = CURRENT_TIMESTAMP
            """;
        
        jdbcTemplate.update(sql,
                preferences.getUserId(),
                preferences.getNotificationEnabled(),
                preferences.getEmailNotifications(),
                preferences.getPushNotifications(),
                toJsonArray(preferences.getPreferredCategories()),
                preferences.getQuietHoursStart(),
                preferences.getQuietHoursEnd(),
                preferences.getTimezone());

        return preferences;
    }

    /**
     * **NEW:** Find all distinct user IDs from the preferences.
     * This provides a list of all known users in the system.
     * @return A list of unique user IDs.
     */
    public List<String> findAllUserIds() {
        String sql = "SELECT DISTINCT user_id FROM user_preferences ORDER BY user_id";
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * Helper method to parse JSON array from database
     */
    private List<String> parseJsonArray(String json) {
        if (json == null || json.trim().isEmpty()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, 
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Helper method to convert list to JSON array string
     */
    private String toJsonArray(List<String> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
        } catch (Exception e) {
            return null;
        }
    }
}
