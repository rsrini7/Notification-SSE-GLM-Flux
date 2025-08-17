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

                log.info("Activating and fanning out scheduled broadcast ID: {}", broadcast.getId());
                broadcastLifecycleService.processReadyBroadcast(broadcast.getId());

            } catch (Exception e) {
                log.error("Error activating scheduled broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}