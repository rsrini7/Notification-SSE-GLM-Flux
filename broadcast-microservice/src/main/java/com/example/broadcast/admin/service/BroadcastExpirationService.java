package com.example.broadcast.admin.service;
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
public class BroadcastExpirationService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastLifecycleService broadcastLifecycleService;

    /**
     * Periodically checks for active broadcasts that have passed their expiration time.
     * Runs every minute.
     * The SchedulerLock ensures that this method is executed by only one pod at a time in a multi-node setup.
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    @SchedulerLock(name = "processExpiredBroadcasts", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void processExpiredBroadcasts() {
        log.info("Checking for expired broadcasts to process...");
        List<BroadcastMessage> broadcastsToExpire = broadcastRepository.findExpiredBroadcasts(ZonedDateTime.now(ZoneOffset.UTC)); 

        if (broadcastsToExpire.isEmpty()) {
            log.info("No expired broadcasts to process at this time.");
            return; 
        }

        log.info("Found {} broadcasts to expire.", broadcastsToExpire.size());
        for (BroadcastMessage broadcast : broadcastsToExpire) { 
            try {
                // Delegate the expiration logic to the new lifecycle service
                broadcastLifecycleService.expireBroadcast(broadcast.getId());
            } catch (Exception e) {
                log.error("Error expiring broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}