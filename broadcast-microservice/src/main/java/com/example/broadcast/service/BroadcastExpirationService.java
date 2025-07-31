package com.example.broadcast.service;

import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Service to handle the lifecycle of broadcasts, specifically expiration.
 * This service runs a scheduled job to check for and process expired messages.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastExpirationService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastService broadcastService;

    /**
     * Periodically checks for active broadcasts that have passed their expiration time.
     * Runs every minute.
     */
    @Scheduled(fixedRate = 60000) // Run every minute
    @Transactional
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
                // Delegate the expiration logic to the main BroadcastService
                broadcastService.expireBroadcast(broadcast.getId());
            } catch (Exception e) {
                log.error("Error expiring broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}
