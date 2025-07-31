package com.example.broadcast.service;

import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.model.UserPreferences;
import com.example.broadcast.repository.UserPreferencesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A service dedicated to handling the logic of targeting users for broadcasts.
 * It determines which users should receive a message and filters them based on
 * their notification preferences.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastTargetingService {

    private final UserPreferencesRepository userPreferencesRepository;

    /**
     * Creates a list of UserBroadcastMessage entities for all targeted users of a broadcast.
     * This method encapsulates the logic for determining target users and filtering them
     * based on their individual notification preferences.
     *
     * @param broadcast The broadcast message to be sent.
     * @return A list of UserBroadcastMessage objects ready to be saved to the database.
     */
    public List<UserBroadcastMessage> createUserBroadcastMessagesForBroadcast(BroadcastMessage broadcast) {
        List<String> targetUserIds = determineTargetUsers(broadcast);

        log.info("Broadcast ID {}: Determined {} initial target users.", broadcast.getId(), targetUserIds.size());

        List<UserBroadcastMessage> userMessages = targetUserIds.stream()
            .filter(userId -> {
                UserPreferences preferences = userPreferencesRepository.findByUserId(userId).orElse(null);
                boolean shouldDeliver = shouldDeliverToUser(preferences);
                if (!shouldDeliver) {
                    log.debug("Broadcast ID {}: Skipping user {} due to their notification preferences.", broadcast.getId(), userId);
                }
                return shouldDeliver;
            })
            .map(userId -> UserBroadcastMessage.builder()
                .broadcastId(broadcast.getId())
                .userId(userId)
                .deliveryStatus("PENDING")
                .readStatus("UNREAD")
                .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                .updatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build())
            .collect(Collectors.toList());
        
        log.info("Broadcast ID {}: After filtering by preferences, {} users will receive the message.", broadcast.getId(), userMessages.size());
        return userMessages;
    }

    /**
     * Determines the initial list of target user IDs based on the broadcast's targeting type.
     * In a real-world application, this would involve calls to a dedicated User Service
     * to resolve roles or fetch all active users.
     *
     * @param broadcast The broadcast message.
     * @return A list of user IDs.
     */
    private List<String> determineTargetUsers(BroadcastMessage broadcast) {
        // In a real implementation, this would query a user service or database.
        // For this example, we return sample user IDs based on the target type.
        log.info("Broadcast ID {}: Target type is {}", broadcast.getId(), broadcast.getTargetType());
        switch (broadcast.getTargetType()) {
            case "ALL":
                // In production, this would query a user service for all active users.
                return List.of("user-001", "user-002", "user-003", "user-004", "user-005");
            case "SELECTED":
                // Return specifically selected users.
                return broadcast.getTargetIds() != null ? broadcast.getTargetIds() : List.of();
            case "ROLE":
                // In production, this would query a user service for users with the specified roles.
                return List.of("user-001", "user-002", "user-003"); // Mock response for users in a role
            default:
                return List.of();
        }
    }

    /**
     * Checks if a message should be delivered to a user based on their preferences.
     *
     * @param preferences The user's notification preferences.
     * @return true if the message should be delivered, false otherwise.
     */
    private boolean shouldDeliverToUser(UserPreferences preferences) {
        log.info("User ID {}: Notification preferences are {}", preferences.getUserId(), preferences);
        if (preferences == null || preferences.getNotificationEnabled() == null) {
            return true; // Default to deliver if no preferences are set.
        }
        if (!preferences.getNotificationEnabled()) {
            return false;
        }
        if (preferences.getQuietHoursStart() != null && preferences.getQuietHoursEnd() != null) {
            // In a real implementation, we would use the user's timezone from preferences.
            LocalTime now = LocalTime.now(); 
            return !isInQuietHours(now, preferences.getQuietHoursStart(), preferences.getQuietHoursEnd());
        }
        return true;
    }

    /**
     * Checks if the current time falls within the user's defined quiet hours.
     *
     * @param now The current time.
     * @param start The start of the quiet hours.
     * @param end The end of the quiet hours.
     * @return true if it is currently quiet hours, false otherwise.
     */
    private boolean isInQuietHours(LocalTime now, LocalTime start, LocalTime end) {
        log.info("Current time: {}, Quiet hours: {} to {}", now, start, end);
        // Handles overnight quiet hours (e.g., 22:00 to 06:00)
        if (start.isAfter(end)) {
            return now.isAfter(start) || now.isBefore(end);
        }
        // Handles same-day quiet hours (e.g., 09:00 to 17:00)
        return now.isAfter(start) && now.isBefore(end);
    }
}
