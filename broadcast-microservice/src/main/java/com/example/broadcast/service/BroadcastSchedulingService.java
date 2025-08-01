package com.example.broadcast.service;

import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.exception.UserServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final BroadcastService broadcastService;
    private static final int BATCH_LIMIT = 100; // Process up to 100 jobs per run

    @Scheduled(fixedRate = 60000) // Run every minute
    @Transactional(noRollbackFor = UserServiceUnavailableException.class)
    public void processScheduledBroadcasts() {
        log.info("Checking for scheduled broadcasts to process...");
        
        List<BroadcastMessage> broadcastsToProcess = broadcastRepository.findAndLockScheduledBroadcastsToProcess(ZonedDateTime.now(ZoneOffset.UTC), BATCH_LIMIT);

        if (broadcastsToProcess.isEmpty()) {
            log.info("No scheduled broadcasts to process at this time.");
            return;
        }

        log.info("Found and locked {} scheduled broadcasts to process.", broadcastsToProcess.size());
        for (BroadcastMessage broadcast : broadcastsToProcess) {
            try {
                broadcastService.processScheduledBroadcast(broadcast.getId());
            } catch (Exception e) {
                log.error("Error processing scheduled broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}
