package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastActivationService {

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

        // 2. ACTIVATION (Fan-out-on-Write): Activate PRODUCT broadcasts that are prepared and due
        List<BroadcastMessage> readyBroadcasts = broadcastRepository.findAndLockReadyBroadcastsToProcess(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : readyBroadcasts) {
            broadcastLifecycleService.processReadyBroadcast(broadcast.getId());
        }

        // 3. ACTIVATION (Fan-out-on-Read): Activate ALL, ROLE, or SELECTED broadcasts that are due
        List<BroadcastMessage> scheduledFanOutOnRead = broadcastRepository.findAndLockScheduledFanOutOnReadBroadcasts(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : scheduledFanOutOnRead) {
            broadcastLifecycleService.activateAndPublishFanOutOnReadBroadcast(broadcast.getId());
        }
    }
}