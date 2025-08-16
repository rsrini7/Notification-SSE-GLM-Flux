// UPDATED FILE
package com.example.broadcast.admin.service;

import com.example.broadcast.shared.exception.UserServiceUnavailableException;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastSchedulingService {

    private final BroadcastRepository broadcastRepository;
    // We now need the BroadcastLifecycleService to reuse its fan-out method
    private final BroadcastLifecycleService broadcastLifecycleService;

    private static final int BATCH_LIMIT = 100;

    @Scheduled(fixedRate = 60000)
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    @SchedulerLock(name = "processScheduledBroadcasts", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void processScheduledBroadcasts() {
        log.info("Checking for READY broadcasts to activate...");
        List<BroadcastMessage> broadcastsToProcess = broadcastRepository.findAndLockReadyBroadcastsToProcess(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);

        if (broadcastsToProcess.isEmpty()) {
            log.info("No ready broadcasts are due for activation at this time.");
            return;
        }

        log.info("Found and locked {} ready broadcasts to activate.", broadcastsToProcess.size());
        for (BroadcastMessage broadcast : broadcastsToProcess) {
            try {
                // *** THIS IS THE FIX ***
                // Instead of calling a separate method, we'll bring the activation and fan-out logic directly here.
                log.info("Activating and fanning out scheduled broadcast ID: {}", broadcast.getId());

                // 1. Set status to ACTIVE
                broadcast.setStatus("ACTIVE");
                broadcast.setUpdatedAt(ZonedDateTime.now(ZoneOffset.UTC));
                broadcastRepository.update(broadcast);

                // 2. Get the pre-computed list of users
                List<String> targetUserIds = broadcastLifecycleService.getTargetUserIds(broadcast.getId());

                // 3. Call the fan-out method from BroadcastLifecycleService, which now works for all types
                broadcastLifecycleService.triggerFanOut(broadcast, targetUserIds);

            } catch (Exception e) {
                log.error("Error activating scheduled broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}