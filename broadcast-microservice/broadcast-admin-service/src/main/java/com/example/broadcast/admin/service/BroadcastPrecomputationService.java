// file: broadcast-microservice/broadcast-admin-service/src/main/java/com/example/broadcast/admin/service/BroadcastPrecomputationService.java

package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.util.Constants; // NEW IMPORT
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
public class BroadcastPrecomputationService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastTargetingService broadcastTargetingService;
    private final AppProperties appProperties;

    private static final long BUFFER_MILLISECONDS = 120_000L;

    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "precomputeScheduledBroadcasts", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    @Transactional
    public void findAndPrepareBroadcasts() {
         // 1. Get the configured maximum fetch delay.
        long fetchDelayMs = appProperties.getSimulation().getUserFetchDelayMs();
        
        // 2. Calculate the total window needed: the delay + a 2-minute buffer.
        long totalWindowMs = fetchDelayMs + BUFFER_MILLISECONDS;
        ZonedDateTime prefetchCutoff = ZonedDateTime.now(ZoneOffset.UTC).plus(totalWindowMs, ChronoUnit.MILLIS);

        List<BroadcastMessage> broadcastsToPrepare = broadcastRepository.findScheduledProductBroadcastsWithinWindow(prefetchCutoff);

        if (broadcastsToPrepare.isEmpty()) {
            return;
        }

        log.info("Found {} scheduled broadcasts to begin pre-computation.", broadcastsToPrepare.size());
        for (BroadcastMessage broadcast : broadcastsToPrepare) {
            // --- THIS IS THE CRITICAL FIX ---
            // 1. Claim the broadcast immediately by updating its status within this synchronous transaction.
            broadcastRepository.updateStatus(broadcast.getId(), Constants.BroadcastStatus.PREPARING.name());
            log.info("Claimed broadcast ID: {} by setting status to PREPARING.", broadcast.getId());

            // 2. Now, safely call the asynchronous method to do the long-running work.
            broadcastTargetingService.precomputeAndStoreTargetUsers(broadcast.getId());
        }
    }
}