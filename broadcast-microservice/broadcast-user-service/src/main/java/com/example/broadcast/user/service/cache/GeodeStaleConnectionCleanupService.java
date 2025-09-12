package com.example.broadcast.user.service.cache;

import com.example.broadcast.shared.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class GeodeStaleConnectionCleanupService {

    private final CacheService cacheService;
    private final AppProperties appProperties;

    /**
     * A scheduled, cluster-wide job that finds and cleans up any stale or orphaned
     * user connections in Geode. The SchedulerLock ensures only one pod runs this
     * at a time to prevent race conditions.
     */
    @Scheduled(fixedRate = 60000) // Run every 60 seconds
    @SchedulerLock(name = "cleanupStaleGeodeConnections", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void cleanupStaleConnections() {
        log.trace("Running cluster-wide stale connection cleanup job...");
        try {
            long nowEpochMillis = OffsetDateTime.now().toInstant().toEpochMilli();
            long staleThreshold = nowEpochMillis - appProperties.getSse().getClientTimeoutThreshold();

            log.info("staleThreshold : {}", staleThreshold);
            // 1. Get ALL stale connection IDs from across the cluster
            Set<String> staleConnectionIds = cacheService.getStaleConnectionIds(staleThreshold);

            log.info("staleConnectionIds : {}", staleConnectionIds);

            if (staleConnectionIds.isEmpty()) {
                log.trace("No stale connections found.");
                return;
            }

            log.warn("Found {} stale connections to clean up.", staleConnectionIds.size());
            for (String staleConnectionId : staleConnectionIds) {
                // Get the heartbeat entry, which we know exists and is stale.
                cacheService.getHeartbeatEntry(staleConnectionId).ifPresent(heartbeat -> {
                    String userId = heartbeat.getUserId();
                    log.info("Cleaning up stale connection {} for user {}", staleConnectionId, userId);
                    // Directly call the unregister method with the known information.
                    cacheService.unregisterUserConnection(userId, staleConnectionId);
                });
            }
        } catch (Exception e) {
            log.error("Error during stale connection cleanup job", e);
        }
    }
}