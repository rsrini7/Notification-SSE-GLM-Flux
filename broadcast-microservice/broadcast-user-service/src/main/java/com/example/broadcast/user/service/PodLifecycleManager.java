package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class PodLifecycleManager {

    private final AppProperties appProperties;
    private final CacheService cacheService;
    private final ClientCache clientCache;
    @Qualifier("podHeartbeatsRegion")
    private final Region<String, Long> podHeartbeatsRegion;
    @Qualifier("podConnectionsRegion")
    private final Region<String, Set<String>> podConnectionsRegion;

    /**
     * Per-pod task: This runs on every instance of the service.
     * Each pod updates its own heartbeat timestamp in the shared cache.
     */
    @Scheduled(fixedRate = 30000) // Every 30 seconds
    public void updateSelfHeartbeat() {
        if (clientCache.isClosed()) return;
        try {
            String podId = appProperties.getPod().getId();
            podHeartbeatsRegion.put(podId, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("Could not update pod heartbeat. May be shutting down.", e);
        }
    }

    /**
     * Cluster-wide task: This runs on only one instance at a time.
     * It finds pods with stale heartbeats and cleans up their orphaned data.
     */
    @Scheduled(fixedRate = 60000) // Every 1 minute
    @SchedulerLock(name = "reapStalePods", lockAtLeastFor = "PT55S", lockAtMostFor = "PT59S")
    public void reapStalePods() {
        if (clientCache.isClosed()) return;
        log.debug("Running stale pod cleanup task...");
        try {
            long staleThreshold = System.currentTimeMillis() - 90_000; // 90-second threshold for a pod to be considered dead
            Set<String> podsWithConnections = podConnectionsRegion.keySetOnServer();
            
            for (String podId : podsWithConnections) {
                Long lastHeartbeat = podHeartbeatsRegion.get(podId);

                // If a pod has connection data but no heartbeat, or its heartbeat is stale, it's a zombie.
                if (lastHeartbeat == null || lastHeartbeat < staleThreshold) {
                    log.warn("Detected stale/dead pod: {}. Initiating cleanup of its resources.", podId);
                    cacheService.cleanupDeadPod(podId);
                }
            }
        } catch (Exception e) {
            log.error("Error during stale pod reaping task: {}", e.getMessage(), e);
        }
    }
}