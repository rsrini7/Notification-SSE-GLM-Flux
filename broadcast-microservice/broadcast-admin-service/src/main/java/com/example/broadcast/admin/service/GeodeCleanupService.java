package com.example.broadcast.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * A component responsible for clearing all Geode regions during a graceful shutdown.
 * This is intended for development environments to ensure the cache is in sync
 * with a database that is wiped and re-initialized on each application start.
 * It is only active when the "admin-only" profile is enabled.
 */
@Component
@Slf4j
@RequiredArgsConstructor
@Profile("admin-only")
public class GeodeCleanupService implements ApplicationListener<ContextClosedEvent> {

    private final ClientCache clientCache;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Application shutdown detected. Cleaning up all Geode regions...");

        if (clientCache.isClosed()) {
            log.warn("Geode client cache is already closed. Skipping cleanup.");
            return;
        }

        try {
            Set<Region<?, ?>> regions = clientCache.rootRegions();
            log.info("Found {} regions to clear: {}", regions.size(), regions.stream().map(Region::getName).toList());

            for (Region<?, ?> region : regions) {
                // Use isEmpty() for a simple check.
                if (!region.isEmpty()) { 
                    log.debug("Clearing all entries from region '{}'...", region.getName());
                    region.clear();
                    log.info("Successfully cleared region: {}", region.getName());
                } else {
                    log.debug("Region '{}' is already empty. Nothing to clear.", region.getName());
                }
            }
            log.info("Geode region cleanup completed successfully.");
        } catch (Exception e) {
            log.error("An error occurred during Geode region cleanup.", e);
        }
    }
}