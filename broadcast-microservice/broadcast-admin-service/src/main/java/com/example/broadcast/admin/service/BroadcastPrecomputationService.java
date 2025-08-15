// file: broadcast-microservice/broadcast-admin-service/src/main/java/com/example/broadcast/admin/service/BroadcastPrecomputationService.java

package com.example.broadcast.admin.service;

import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
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

        for (BroadcastMessage broadcast : broadcastsToPrepare) {
            log.info("Starting pre-computation for scheduled broadcast ID: {}", broadcast.getId());
            // This call is asynchronous, so the scheduler can continue its work immediately
            broadcastTargetingService.precomputeAndStoreTargetUsers(broadcast.getId());
        }
    }
}