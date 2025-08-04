package com.example.broadcast.service;

import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.model.UserPreferences;
import com.example.broadcast.repository.UserPreferencesRepository;
import com.example.broadcast.util.Constants.DeliveryStatus;
import com.example.broadcast.util.Constants.ReadStatus;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A service dedicated to handling the logic of targeting users for broadcasts.
 * It determines which users should receive a message and filters them based on
 * their notification preferences. This service is now protected by a Circuit Breaker and Bulkhead.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastTargetingService {

    private final UserPreferencesRepository userPreferencesRepository;
    private final UserService userService;

    /**
     * Creates a list of UserBroadcastMessage entities for all targeted users of a broadcast.
     * This method is now protected by a Circuit Breaker and a Bulkhead. If the call to the
     * UserService fails, the circuit breaker will open, and the fallback method will be invoked.
     *
     * @param broadcast The broadcast message to be sent.
     * @return A list of UserBroadcastMessage objects ready to be saved to the database.
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackCreateUserBroadcastMessagesForBroadcast")
    @Bulkhead(name = "userService")
    public List<UserBroadcastMessage> createUserBroadcastMessagesForBroadcast(BroadcastMessage broadcast) {
        List<String> targetUserIds = determineTargetUsers(broadcast);

        log.info("Broadcast ID {}: Determined {} initial target users.", broadcast.getId(), targetUserIds.size());
        if (targetUserIds.isEmpty()) {
            return Collections.emptyList();
        }

        // START OF FIX: Solve N+1 query problem
        // Fetch all preferences in a single batch query and store in a map for fast lookup.
        Map<String, UserPreferences> preferencesMap = userPreferencesRepository.findByUserIdIn(targetUserIds)
                .stream()
                .collect(Collectors.toMap(UserPreferences::getUserId, pref -> pref));
        // END OF FIX

        List<UserBroadcastMessage> userMessages = targetUserIds.stream()
            .filter(userId -> {
                // START OF FIX: Look up preferences from the in-memory map instead of hitting the DB.
                UserPreferences preferences = preferencesMap.get(userId);
                // END OF FIX
                boolean shouldDeliver = shouldDeliverToUser(preferences);
                if (!shouldDeliver) {
                    log.debug("Broadcast ID {}: Skipping user {} due to their notification preferences.", broadcast.getId(), userId);
                }
                return shouldDeliver;
            })
            .map(userId -> UserBroadcastMessage.builder()
                .broadcastId(broadcast.getId())
                .userId(userId)
                .deliveryStatus(DeliveryStatus.PENDING.name())
                .readStatus(ReadStatus.UNREAD.name())
                .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                .updatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .build())
            .collect(Collectors.toList());
        
        log.info("Broadcast ID {}: After filtering by preferences, {} users will receive the message.", broadcast.getId(), userMessages.size());
        return userMessages;
    }

    /**
     * Determines the initial list of target user IDs by calling the external UserService.
     * This is now a helper method and does not need resilience annotations.
     *
     * @param broadcast The broadcast message.
     * @return A list of user IDs.
     */
    private List<String> determineTargetUsers(BroadcastMessage broadcast) {
        log.info("Broadcast ID {}: Calling UserService to determine target users. Target type is {}", broadcast.getId(), broadcast.getTargetType());
        switch (broadcast.getTargetType()) {
            case "ALL":
                return userService.getAllUserIds();
            case "SELECTED":
                return broadcast.getTargetIds() != null ? broadcast.getTargetIds() : List.of();
            case "ROLE":
                // Assuming targetIds contains the role name for simplicity
                String role = broadcast.getTargetIds() != null && !broadcast.getTargetIds().isEmpty() ? broadcast.getTargetIds().get(0) : "default";
                return userService.getUserIdsByRole(role);
            default:
                return List.of();
        }
    }

    /**
     * Fallback method for createUserBroadcastMessagesForBroadcast. This method is executed when the
     * circuit breaker for the 'userService' is open.
     *
     * @param broadcast The original broadcast message.
     * @param t The exception that caused the fallback.
     * @return An empty list of UserBroadcastMessage to prevent the broadcast from being sent.
     */
    public List<UserBroadcastMessage> fallbackCreateUserBroadcastMessagesForBroadcast(BroadcastMessage broadcast, Throwable t) {
        log.error("Circuit breaker opened for user service. Falling back for broadcast ID {}. Error: {}", broadcast.getId(), t.getMessage());
        // Fallback logic: return an empty list to prevent sending the broadcast.
        // In a real-world scenario, you might return a cached list of users or trigger an alert.
        return Collections.emptyList();
    }

    /**
     * Checks if a message should be delivered to a user based on their preferences.
     *
     * @param preferences The user's notification preferences.
     * @return true if the message should be delivered, false otherwise.
     */
    private boolean shouldDeliverToUser(UserPreferences preferences) {
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
        // Handles overnight quiet hours (e.g., 22:00 to 06:00)
        if (start.isAfter(end)) {
            return now.isAfter(start) || now.isBefore(end);
        }
        // Handles same-day quiet hours (e.g., 09:00 to 17:00)
        return now.isAfter(start) && now.isBefore(end);
    }
}