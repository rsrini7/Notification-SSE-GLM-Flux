package com.example.broadcast.admin.service;

import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.model.UserPreferences;
import com.example.broadcast.shared.repository.UserPreferencesRepository;
import com.example.broadcast.shared.service.UserService;

import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.DeliveryStatus;
import com.example.broadcast.shared.util.Constants.ReadStatus;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastTargetingService {

    private final UserPreferencesRepository userPreferencesRepository;
    private final UserService userService;

    /**
     * Creates UserBroadcastMessage entities for a 'SELECTED' user broadcast.
     * This method is now specifically for the "fan-out-on-write" strategy.
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackCreateUserBroadcastMessagesForBroadcast")
    @Bulkhead(name = "userService")
    public List<UserBroadcastMessage> createUserBroadcastMessagesForBroadcast(BroadcastMessage broadcast) {
        if (!Constants.TargetType.SELECTED.name().equals(broadcast.getTargetType())) {
            return Collections.emptyList();
        }

        List<String> targetUserIds = broadcast.getTargetIds() != null ? broadcast.getTargetIds() : List.of();
        log.info("Broadcast ID {}: Determined {} initial target users for SELECTED broadcast.", broadcast.getId(), targetUserIds.size());

        if (targetUserIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        return createMessagesWithPreferenceFiltering(broadcast, targetUserIds);
    }

    /**
     * Efficiently counts the number of target users for 'ALL' or 'ROLE' broadcasts without fetching all user IDs.
     * This is used for the "fan-out-on-read" strategy.
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackCountTargetUsers")
    @Bulkhead(name = "userService")
    public int countTargetUsers(BroadcastMessage broadcast) {
        String targetType = broadcast.getTargetType();
        
        if (Constants.TargetType.ALL.name().equals(targetType)) {
            // In a real system, this would be a much more efficient COUNT query against the user service.
            return userService.getAllUserIds().size();
        } else if (Constants.TargetType.ROLE.name().equals(targetType)) {
            // Similarly, this would be an efficient count.
            return broadcast.getTargetIds().stream()
                .mapToInt(role -> userService.getUserIdsByRole(role).size())
                .sum();
        }
        
        return 0;
    }

    private List<UserBroadcastMessage> createMessagesWithPreferenceFiltering(BroadcastMessage broadcast, List<String> targetUserIds) {
        Map<String, UserPreferences> preferencesMap = userPreferencesRepository.findByUserIdIn(targetUserIds)
                .stream()
                .collect(Collectors.toMap(UserPreferences::getUserId, pref -> pref));

        List<UserBroadcastMessage> userMessages = targetUserIds.stream()
            .filter(userId -> {
                UserPreferences preferences = preferencesMap.get(userId);
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
    
    public List<UserBroadcastMessage> fallbackCreateUserBroadcastMessagesForBroadcast(BroadcastMessage broadcast, Throwable t) {
        log.error("Circuit breaker opened for user service. Falling back for broadcast ID {}. Error: {}", broadcast.getId(), t.getMessage());
        return Collections.emptyList();
    }
    
    public int fallbackCountTargetUsers(BroadcastMessage broadcast, Throwable t) {
        log.error("Circuit breaker opened for user service during count. Falling back for broadcast ID {}. Error: {}", broadcast.getId(), t.getMessage());
        return 0;
    }

    private boolean shouldDeliverToUser(UserPreferences preferences) {
        if (preferences == null || preferences.getNotificationEnabled() == null) {
            return true;
        }
        if (!preferences.getNotificationEnabled()) {
            return false;
        }
        if (preferences.getQuietHoursStart() != null && preferences.getQuietHoursEnd() != null) {
            LocalTime now = LocalTime.now(); 
            return !isInQuietHours(now, preferences.getQuietHoursStart(), preferences.getQuietHoursEnd());
        }
        return true;
    }

    private boolean isInQuietHours(LocalTime now, LocalTime start, LocalTime end) {
        if (start.isAfter(end)) {
            return now.isAfter(start) || now.isBefore(end);
        }
        return now.isAfter(start) && now.isBefore(end);
    }
}