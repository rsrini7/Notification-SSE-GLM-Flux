package com.example.broadcast.user.scheduler;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.geode.cache.Region;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "broadcast.geode.regions.user-messages-inbox",
    name = "cleanup-enabled",
    havingValue = "true",
    matchIfMissing = false // Ensures the job is disabled if the property is not set
)
public class GeodeRegionCleanupService {

    @Qualifier("userMessagesInboxRegion")
    private final Region<String, List<UserMessageInbox>> userMessagesInboxRegion;
    private final AppProperties appProperties;

    @Monitored("scheduler")
    @Scheduled(fixedRateString = "${broadcast.geode.regions.user-messages-inbox.cleanup-job-rate-ms}")
    @SchedulerLock(name = "cleanupUserInboxRegion", lockAtLeastFor = "PT1M", lockAtMostFor = "PT4M")
    public void cleanupUserInboxRegion() {
        int threshold = appProperties.getGeode().getRegions().getUserMessagesInbox().getCleanupThreshold();
        log.info("Running Geode 'user-messages-inbox' cleanup job. Threshold: {} entries.", threshold);

        try {
            int currentSize = userMessagesInboxRegion.size();

            if (currentSize <= threshold) {
                log.info("Region size ({}) is within the threshold ({}). No cleanup needed.", currentSize, threshold);
                return;
            }

            int excessEntries = currentSize - threshold;
            log.warn("Region size ({}) exceeds threshold ({}). Preparing to remove {} excess entries.",
                    currentSize, threshold, excessEntries);

            // Fetch all keys from the server. This is more efficient than fetching all entries.
            Set<String> allKeys = userMessagesInboxRegion.keySetOnServer();
            List<String> keysToRemove = new ArrayList<>(allKeys);

            // Shuffle the list to randomize which keys are removed, approximating LRU without the overhead.
            Collections.shuffle(keysToRemove);

            // Select the sublist of excess keys to remove
            if (keysToRemove.size() > excessEntries) {
                keysToRemove = keysToRemove.subList(0, excessEntries);
            }

            log.info("Attempting to remove {} entries from 'user-messages-inbox' region...", keysToRemove.size());
            userMessagesInboxRegion.removeAll(keysToRemove);
            log.info("Successfully removed {} entries from 'user-messages-inbox' region.", keysToRemove.size());

        } catch (Exception e) {
            log.error("Error during Geode 'user-messages-inbox' cleanup job.", e);
        }
    }
}