package com.example.broadcast.admin.service;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastDeliveredCleanupService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;

    /**
     * Periodically cleans up user messages for broadcasts that are in a final state
     * (CANCELLED or EXPIRED) and are older than a one-hour threshold.
     * This service deletes entries that were delivered but never read,
     * preserving a record only for messages the user explicitly interacted with.
     */
    @Monitored("scheduler")
    @Scheduled(cron = "0 0 * * * *") // Run at the top of every hour
    @SchedulerLock(name = "cleanupDeliveredMessages", lockAtLeastFor = "PT5M", lockAtMostFor = "PT15M")
    @Transactional
    public void cleanupFinalizedBroadcasts() {
        log.info("Starting cleanup job for unread messages from finalized broadcasts...");
        
        // Find broadcasts that were finalized more than an hour ago to avoid race conditions.
        OffsetDateTime cutoff = OffsetDateTime.now().minus(1, ChronoUnit.HOURS);
        List<BroadcastMessage> broadcastsToClean = broadcastRepository.findFinalizedBroadcastsForCleanup(cutoff);

        if (broadcastsToClean.isEmpty()) {
            log.info("No finalized broadcasts found that require cleanup of user messages.");
            return;
        }

        for (BroadcastMessage broadcast : broadcastsToClean) {
            int deletedCount = userBroadcastRepository.deleteUnreadMessagesByBroadcastId(broadcast.getId());
            if (deletedCount > 0) {
                log.info("Cleaned up {} unread user messages for finalized broadcast ID: {}", deletedCount, broadcast.getId());
            }
        }
        log.info("Finished cleanup job for unread messages from finalized broadcasts.");
    }
}