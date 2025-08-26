package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.broadcast.shared.service.UserService;


import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastActivationService {

    private final UserService userService;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastLifecycleService broadcastLifecycleService;
    private final BroadcastTargetingService broadcastTargetingService;
    private final AppProperties appProperties;

    private static final long PRECOMPUTATION_BUFFER_MS = 120_000L; // 2-minute buffer
    private static final int BATCH_LIMIT = 100;

    /**
     * A unified scheduled task that runs every minute to manage the lifecycle of
     * scheduled broadcasts. It handles pre-computation for complex broadcasts
     * and activates any broadcast that is due.
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    @SchedulerLock(name = "processDueBroadcasts", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void processDueBroadcasts() {
        log.debug("Running unified scheduler for broadcast activation...");

        // 1. PRE-COMPUTATION: Find upcoming PRODUCT broadcasts that need preparation
        long fetchDelayMs = appProperties.getSimulation().getUserFetchDelayMs();
        long totalWindowMs = fetchDelayMs + PRECOMPUTATION_BUFFER_MS;
        ZonedDateTime prefetchCutoff = ZonedDateTime.now(ZoneOffset.UTC).plus(totalWindowMs, ChronoUnit.MILLIS);

        List<BroadcastMessage> broadcastsToPrepare = broadcastRepository.findScheduledProductBroadcastsWithinWindow(prefetchCutoff);
        for (BroadcastMessage broadcast : broadcastsToPrepare) {
            broadcastRepository.updateStatus(broadcast.getId(), Constants.BroadcastStatus.PREPARING.name());
            log.info("Claimed broadcast ID: {} for pre-computation by setting status to PREPARING.", broadcast.getId());
            broadcastTargetingService.precomputeAndStoreTargetUsers(broadcast.getId());
        }

        // Phase 2: Activation (Fan-out-on-Write for PRODUCT broadcasts that are prepared and due)
        List<BroadcastMessage> readyBroadcasts = broadcastRepository.findAndLockReadyBroadcastsToProcess(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : readyBroadcasts) {
            broadcastLifecycleService.processReadyBroadcast(broadcast.getId());
        }

        // Phase 3: Activation (Now includes Fan-out-on-Write for SCHEDULED 'SELECTED' and 'ROLE' broadcasts)
        List<BroadcastMessage> scheduledFanOuts = broadcastRepository.findAndLockScheduledFanOutBroadcasts(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : scheduledFanOuts) {
            if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
                // Fan-out-on-Read: Simple activation
                broadcastLifecycleService.activateAndPublishFanOutOnReadBroadcast(broadcast.getId());
            } else {
                // Fan-out-on-Write: Perform the fan-out now, then activate
                log.info("Activating scheduled fan-out-on-write broadcast ID: {}", broadcast.getId());
                
                // 1. Determine target users
                List<String> targetUserIds = determineTargetUsersForWrite(broadcast);
                
                // 2. Persist the user message records
                if (!targetUserIds.isEmpty()) {
                    persistUserMessages(broadcast, targetUserIds);
                }
                
                // 3. Activate and publish the single orchestration event
                broadcastLifecycleService.activateAndPublishFanOutOnReadBroadcast(broadcast.getId());
            }
        }
    }

    private void persistUserMessages(BroadcastMessage broadcast, List<String> userIds) {
        log.info("Persisting {} user_broadcast_messages records for scheduled broadcast ID: {}", userIds.size(), broadcast.getId());
        List<UserBroadcastMessage> userMessages = userIds.stream()
                .map(userId -> UserBroadcastMessage.builder()
                        .broadcastId(broadcast.getId())
                        .userId(userId)
                        .deliveryStatus(Constants.DeliveryStatus.PENDING.name())
                        .readStatus(Constants.ReadStatus.UNREAD.name())
                        .build())
                .collect(Collectors.toList());

        userBroadcastRepository.batchInsert(userMessages);
        broadcastLifecycleService.initializeStatistics(broadcast.getId(), userIds.size()); // Assuming initializeStatistics is public
    }

    private List<String> determineTargetUsersForWrite(BroadcastMessage broadcast) {
        return switch (Constants.TargetType.valueOf(broadcast.getTargetType())) {
            case SELECTED -> broadcast.getTargetIds();
            case ROLE -> broadcast.getTargetIds().stream()
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
            default -> Collections.emptyList();
        };
    }
}