// file: broadcast-microservice/broadcast-admin-service/src/main/java/com/example/broadcast/admin/service/BroadcastPrecomputationService.java

package com.example.broadcast.admin.service;

import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.util.Constants; // NEW IMPORT
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastPrecomputationService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastTargetingService broadcastTargetingService;

    private static final Duration PREFETCH_WINDOW = Duration.ofMinutes(30);

    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "precomputeScheduledBroadcasts", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    @Transactional
    public void findAndPrepareBroadcasts() {
        ZonedDateTime prefetchCutoff = ZonedDateTime.now(ZoneOffset.UTC).plus(PREFETCH_WINDOW);
        List<BroadcastMessage> broadcastsToPrepare = broadcastRepository.findScheduledBroadcastsWithinWindow(prefetchCutoff);

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