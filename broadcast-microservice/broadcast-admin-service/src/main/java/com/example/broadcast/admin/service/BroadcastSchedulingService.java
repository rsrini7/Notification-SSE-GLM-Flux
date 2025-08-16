// file: broadcast-microservice/broadcast-admin-service/src/main/java/com/example/broadcast/admin/service/BroadcastSchedulingService.java

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
    private final BroadcastLifecycleService broadcastLifecycleService;

    private static final int BATCH_LIMIT = 100;

    /**
     * Periodically processes broadcasts that are in the READY state and are due for activation.
     * The SchedulerLock ensures this only runs on one pod at a time.
     */
    @Scheduled(fixedRate = 60000) // Run every minute
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    @SchedulerLock(name = "processScheduledBroadcasts", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void processScheduledBroadcasts() {
        // MODIFIED: Log message now reflects that it's looking for READY broadcasts.
        log.info("Checking for READY broadcasts to activate...");
        
        // MODIFIED: This now calls the repository method that finds READY broadcasts.
        List<BroadcastMessage> broadcastsToProcess = broadcastRepository.findAndLockReadyBroadcastsToProcess(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);

        if (broadcastsToProcess.isEmpty()) {
            log.info("No ready broadcasts are due for activation at this time.");
            return;
        }

        log.info("Found and locked {} ready broadcasts to activate.", broadcastsToProcess.size());
        for (BroadcastMessage broadcast : broadcastsToProcess) {
            try {
                // MODIFIED: Calls the renamed method in the lifecycle service.
                broadcastLifecycleService.processReadyBroadcast(broadcast.getId());
            } catch (Exception e) {
                log.error("Error activating scheduled broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}