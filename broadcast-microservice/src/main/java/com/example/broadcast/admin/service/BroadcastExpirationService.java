package com.example.broadcast.admin.service;

import com.example.broadcast.shared.dto.UserDisconnectedEvent;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.user.service.SseUserStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    private final UserBroadcastRepository userBroadcastRepository;
    private final SseUserStatusService sseUserStatusService;

    @Async
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleUserDisconnected(UserDisconnectedEvent event) {
        log.info("User disconnected, checking for fire-and-forget broadcasts to expire for user: {}", event.getUserId());
        checkAndExpireFireAndForgetBroadcast(event.getUserId());
    }

    @Transactional
    public void checkAndExpireFireAndForgetBroadcast(String userId) {
        log.info("Checking for active fire-and-forget broadcasts for user {}", userId);
        List<Long> broadcastIds = userBroadcastRepository.findActiveBroadcastIdsByUserId(userId);
        log.info("Found {} active broadcast(s) for user {}", broadcastIds.size(), userId);

        for (Long broadcastId : broadcastIds) {
            log.info("Checking broadcast {}", broadcastId);
            BroadcastMessage broadcast = broadcastRepository.findById(broadcastId).orElse(null);
            if (broadcast != null && broadcast.isFireAndForget() && Constants.BroadcastStatus.ACTIVE.name().equals(broadcast.getStatus())) {
                log.info("Broadcast {} is a fire-and-forget broadcast.", broadcastId);
                List<String> remainingUsers = userBroadcastRepository.findBroadcastReceivers(broadcastId);
                log.info("Found {} remaining users for broadcast {}", remainingUsers.size(), broadcastId);
                long connectedCount = remainingUsers.stream().filter(sseUserStatusService::isUserConnected).count();
                log.info("Found {} connected users for broadcast {}", connectedCount, broadcastId);

                if (connectedCount == 0) {
                    log.info("Expiring fire-and-forget broadcast {} as no targeted users are connected.", broadcastId);
                    broadcastLifecycleService.expireBroadcast(broadcastId);
                } else {
                    log.info("Fire-and-forget broadcast {} still has {} connected users.", broadcastId, connectedCount);
                }
            } else {
                if (broadcast == null) {
                    log.info("Broadcast with id {} not found.", broadcastId);
                } else {
                    log.info("Broadcast {} is not a fire-and-forget broadcast or is not active.", broadcastId);
                }
            }
        }
    }

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
                broadcastLifecycleService.expireBroadcast(broadcast.getId());
            } catch (Exception e) {
                log.error("Error expiring broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}