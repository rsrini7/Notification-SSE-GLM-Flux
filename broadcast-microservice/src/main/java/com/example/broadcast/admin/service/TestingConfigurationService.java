package com.example.broadcast.admin.service;

import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * A simple, in-memory service to control testing-related configuration.
 * This allows marking broadcasts for failure simulation,
 * enabling/disabling test behaviors without restarting the application.
 *
 * <p>Note: This service is intended for testing purposes only.
 * It should not be used in production environments.</p>

 */
@Service
public class TestingConfigurationService {

    // This Set stores the ID of the broadcast that is designated to fail.
    private final Set<Long> failingBroadcastIds = ConcurrentHashMap.newKeySet();
    
    // This flag "arms" the system to capture the next broadcast ID.
    private final AtomicBoolean armed = new AtomicBoolean(false);

    /**
     * Called by the controller to arm or disarm the failure test.
     */
    public void setArm(boolean shouldArm) {
        this.armed.set(shouldArm);
    }

    /**
     * Called by the controller to check the current armed state for the UI.
     */
    public boolean isArmed() {
        return this.armed.get();
    }

    /**
     * Called by the broadcast creation service. It atomically checks if the system
     * is armed and, if so, consumes the state so it only happens once.
     * @return true if the system was armed.
     */
    public boolean consumeArmedState() {
        return this.armed.getAndSet(false);
    }

    /**
     * Marks a specific broadcast ID for failure simulation.
     */
    public void markBroadcastForFailure(Long broadcastId) {
        if (broadcastId != null) {
            failingBroadcastIds.add(broadcastId);
        }
    }

    /**
     * Checks if a given broadcast ID is marked for failure.
     */
    public boolean isMarkedForFailure(Long broadcastId) {
        return broadcastId != null && failingBroadcastIds.contains(broadcastId);
    }

    /**
     * Clears the failure mark for a broadcast ID after it's been handled by the DLT.
     */
    public void clearFailureMark(Long broadcastId) {
        if (broadcastId != null) {
            failingBroadcastIds.remove(broadcastId);
        }
    }
}