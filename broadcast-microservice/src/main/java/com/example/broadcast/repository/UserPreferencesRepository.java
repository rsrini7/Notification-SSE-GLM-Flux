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

    public Optional<UserPreferences> findByUserId(String userId) {
        String sql = "SELECT * FROM user_preferences WHERE user_id = ?";
        List<UserPreferences> results = jdbcTemplate.query(sql, preferencesRowMapper, userId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public UserPreferences save(UserPreferences preferences) {
        // START OF FIX: Replaced ON CONFLICT with a standard MERGE statement compatible with both H2 and PostgreSQL 15+
        String sql = """
            MERGE INTO user_preferences t
            USING (VALUES (?, ?, ?, ?, ?, ?, ?, ?))
                AS s(user_id, notification_enabled, email_notifications, push_notifications, preferred_categories, quiet_hours_start, quiet_hours_end, timezone)
            ON t.user_id = s.user_id
            WHEN MATCHED THEN
                UPDATE SET notification_enabled = s.notification_enabled,
                           email_notifications = s.email_notifications,
                           push_notifications = s.push_notifications,
                           preferred_categories = s.preferred_categories,
                           quiet_hours_start = s.quiet_hours_start,
                           quiet_hours_end = s.quiet_hours_end,
                           timezone = s.timezone,
                           updated_at = CURRENT_TIMESTAMP
            WHEN NOT MATCHED THEN
                INSERT (user_id, notification_enabled, email_notifications, push_notifications, preferred_categories, quiet_hours_start, quiet_hours_end, timezone)
                VALUES (s.user_id, s.notification_enabled, s.email_notifications, s.push_notifications, s.preferred_categories, s.quiet_hours_start, s.quiet_hours_end, s.timezone)
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
        // END OF FIX
        
        return preferences;
    }

    public List<String> findAllUserIds() {
        String sql = "SELECT DISTINCT user_id FROM user_preferences ORDER BY user_id";
        return jdbcTemplate.queryForList(sql, String.class);
    }

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