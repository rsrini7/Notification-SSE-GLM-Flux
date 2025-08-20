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
        // 1. Process 'READY' broadcasts (The existing logic, which now only applies to PRODUCT type)
        List<BroadcastMessage> readyBroadcasts = broadcastRepository.findAndLockReadyBroadcastsToProcess(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : readyBroadcasts) {
            broadcastLifecycleService.processReadyBroadcast(broadcast.getId());
        }

        // 2. NEW: Process due 'SCHEDULED' fan-out-on-read broadcasts
        List<BroadcastMessage> scheduledFanOutOnRead = broadcastRepository.findAndLockScheduledFanOutOnReadBroadcasts(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);
        for (BroadcastMessage broadcast : scheduledFanOutOnRead) {
            // This new method will update status directly to ACTIVE and create the outbox event
            broadcastLifecycleService.activateAndPublishFanOutOnReadBroadcast(broadcast.getId());
        }
    }
}