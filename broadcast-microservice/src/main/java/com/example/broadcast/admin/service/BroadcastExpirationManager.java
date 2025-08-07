package com.example.broadcast.admin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A dedicated service to handle the expiration of broadcasts in a new transaction.
 * This is used to solve the self-invocation proxy issue for Fire-and-Forget messages.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BroadcastExpirationManager {

    private final BroadcastLifecycleService broadcastLifecycleService;

    /**
     * Triggers the expiration of a broadcast in a guaranteed new transaction.
     * @param broadcastId The ID of the broadcast to expire.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void expireFireAndForgetBroadcast(Long broadcastId) {
        log.info("Executing expiration for Fire-and-Forget broadcast {} in a new transaction.", broadcastId);
        try {
            // This call no longer needs REQUIRES_NEW on its own, as it inherits from this method.
            broadcastLifecycleService.expireBroadcast(broadcastId);
        } catch (Exception e) {
            log.error("Failed to process Fire-and-Forget expiration for broadcast ID: {}", broadcastId, e);
        }
    }
}