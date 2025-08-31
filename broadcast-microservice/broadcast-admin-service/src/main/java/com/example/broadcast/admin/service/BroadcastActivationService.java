package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.service.BroadcastStatisticsService;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.JsonUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Collections;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastActivationService {

    private final UserService userService;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastLifecycleService broadcastLifecycleService;
    private final BroadcastTargetingService broadcastTargetingService;
    private final BroadcastStatisticsService broadcastStatisticsService;
    private final AppProperties appProperties;

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
        OffsetDateTime prefetchCutoff = OffsetDateTime.now(ZoneOffset.UTC).plus(fetchDelayMs, ChronoUnit.MILLIS);

        List<BroadcastMessage> broadcastsToPrepare = broadcastRepository.findScheduledProductBroadcastsWithinWindow(prefetchCutoff);
        for (BroadcastMessage broadcast : broadcastsToPrepare) {
            broadcastRepository.updateStatus(broadcast.getId(), Constants.BroadcastStatus.PREPARING.name());
            log.info("Claimed broadcast ID: {} for pre-computation by setting status to PREPARING.", broadcast.getId());
            broadcastTargetingService.precomputeAndStoreTargetUsers(broadcast.getId());
        }

        // Phase 2: Activation for prepared PRODUCT broadcasts
        // This logic remains the same: find READY broadcasts and activate them.
        List<BroadcastMessage> readyBroadcasts = broadcastRepository.findAndLockReadyBroadcastsToProcess(OffsetDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : readyBroadcasts) {
            broadcastLifecycleService.processReadyBroadcast(broadcast.getId());
        }

        // Phase 3: Activation for all other due scheduled broadcasts (ALL, ROLE, SELECTED)
        List<BroadcastMessage> scheduledFanOuts = broadcastRepository.findAndLockScheduledFanOutBroadcasts(OffsetDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : scheduledFanOuts) {
            if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
                log.info("Activating scheduled 'ALL' broadcast {}.", broadcast.getId());
                // 1. Create the initial statistics record so consumers can update it.
                broadcastStatisticsService.initializeStatistics(broadcast.getId(), 0);
                // 2. Activate the broadcast, which publishes the single Kafka event.
                broadcastLifecycleService.activateAndPublishFanOutOnReadBroadcast(broadcast.getId());
            } else {
                log.info("Activating scheduled fan-out-on-write broadcast ID: {}", broadcast.getId());
                // 1. Determine the target user list.
                List<String> targetUserIds = determineTargetUsersForWrite(broadcast);
                // 2. Persist the user message records and initialize statistics.
                if (!targetUserIds.isEmpty()) {
                    broadcastLifecycleService.persistUserMessages(broadcast, targetUserIds);
                }
                // 3. Activate the broadcast, which now publishes one event PER USER to the outbox.
                broadcastLifecycleService.activateAndPublishFanOutOnWriteBroadcast(broadcast);
            }
        }
    }

    private List<String> determineTargetUsersForWrite(BroadcastMessage broadcast) {
         List<String> targetIds = JsonUtils.parseJsonArray(broadcast.getTargetIds());
        return switch (Constants.TargetType.valueOf(broadcast.getTargetType())) {
            case SELECTED -> targetIds;
            case ROLE -> targetIds.stream()
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
            default -> Collections.emptyList();
        };
    }
}