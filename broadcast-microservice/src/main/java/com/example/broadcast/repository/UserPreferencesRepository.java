package com.example.broadcast.repository;

import com.example.broadcast.model.UserPreferences;
import com.example.broadcast.util.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class UserPreferencesRepository {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 900;

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
                    .preferredCategories(JsonUtils.parseJsonArray(rs.getString("preferred_categories"))) // REFACTORED
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

    public List<UserPreferences> findByUserIdIn(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }

        List<UserPreferences> allPreferences = new ArrayList<>();
        int totalUsers = userIds.size();
        for (int i = 0; i < totalUsers; i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, totalUsers);
            List<String> batch = userIds.subList(i, end);

            String sql = String.format(
                "SELECT * FROM user_preferences WHERE user_id IN (%s)",
                String.join(",", java.util.Collections.nCopies(batch.size(), "?"))
            );
            List<UserPreferences> batchResult = jdbcTemplate.query(sql, preferencesRowMapper, batch.toArray());
            allPreferences.addAll(batchResult);
        }

        return allPreferences;
    }

    public UserPreferences save(UserPreferences preferences) {
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
                JsonUtils.toJsonArray(preferences.getPreferredCategories()), // REFACTORED
                preferences.getQuietHoursStart(),
                preferences.getQuietHoursEnd(),
                preferences.getTimezone());
        return preferences;
    }

    public List<String> findAllUserIds() {
        String sql = "SELECT DISTINCT user_id FROM user_preferences ORDER BY user_id";
        return jdbcTemplate.queryForList(sql, String.class);
    }
}