package com.example.broadcast.user.service;

import com.example.broadcast.shared.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/**
 * A dedicated service to handle the periodic cleanup of the user_sessions table.
 * This service is responsible for long-term data retention policies, separating
 * it from the real-time management of active sessions in SseService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSessionCleanupService {

    private final UserSessionRepository userSessionRepository;
    private static final int RETENTION_DAYS = 3;

    /**
     * Periodically purges old, inactive user sessions from the database.
     * This job runs at 2 AM every day to enforce the data retention policy.
     */
    @Scheduled(cron = "0 0 2 * * *") // Run daily at 2:00 AM
    @Transactional
    public void purgeOldInactiveSessions() {
        log.info("Starting scheduled job to purge old, inactive user sessions...");

        ZonedDateTime threshold = ZonedDateTime.now().minusDays(RETENTION_DAYS);
        log.info("Purging inactive sessions with a disconnected_at date before {}", threshold);

        try {
            int deletedCount = userSessionRepository.deleteInactiveSessionsBefore(threshold);
            if (deletedCount > 0) {
                log.info("Successfully purged {} old, inactive user session records.", deletedCount);
            } else {
                log.info("No old, inactive user sessions found to purge.");
            }
        } catch (Exception e) {
            log.error("Error occurred during the user session cleanup job.", e);
        }
    }
}