package com.example.broadcast.admin.service;

import com.example.broadcast.shared.exception.UserServiceUnavailableException;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
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
    private final BroadcastCreationService broadcastCreationService; 

    private static final int BATCH_LIMIT = 100;

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
                // The main broadcast service now handles the processing logic
                broadcastCreationService.processScheduledBroadcast(broadcast.getId());
            } catch (Exception e) {
                log.error("Error processing scheduled broadcast with ID: {}", broadcast.getId(), e);
            }
        }
    }
}