// file: broadcast-microservice/broadcast-admin-service/src/main/java/com/example/broadcast/admin/service/BroadcastCleanupService.java

package com.example.broadcast.admin.service;

import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastTargetCleanupService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastTargetRepository userBroadcastTargetRepository;

    /**
     * Periodically cleans up the pre-computed user lists for broadcasts that are in a final state
     * (CANCELLED or EXPIRED) and are older than a certain threshold.
     */
    @Scheduled(cron = "0 0 * * * *") // Run at the top of every hour
    @SchedulerLock(name = "cleanupFinalizedBroadcasts", lockAtLeastFor = "PT5M", lockAtMostFor = "PT15M")
    @Transactional
    public void cleanupFinalizedBroadcasts() {
        log.info("Starting cleanup job for finalized broadcast target lists...");
        
        // Find broadcasts that were finalized more than an hour ago to avoid race conditions with in-flight events
        ZonedDateTime cutoff = ZonedDateTime.now().minus(1, ChronoUnit.HOURS);
        List<BroadcastMessage> broadcastsToClean = broadcastRepository.findFinalizedBroadcastsForCleanup(cutoff);

        if (broadcastsToClean.isEmpty()) {
            log.info("No finalized broadcasts found that require cleanup.");
            return;
        }

        for (BroadcastMessage broadcast : broadcastsToClean) {
            int deletedCount = userBroadcastTargetRepository.deleteByBroadcastId(broadcast.getId());
            if (deletedCount > 0) {
                log.info("Cleaned up {} pre-computed user targets for finalized broadcast ID: {}", deletedCount, broadcast.getId());
            }
        }
        log.info("Finished cleanup job for finalized broadcast target lists.");
    }
}