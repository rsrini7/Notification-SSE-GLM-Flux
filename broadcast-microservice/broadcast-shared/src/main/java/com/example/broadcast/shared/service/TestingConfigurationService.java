package com.example.broadcast.shared.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor // Use constructor injection for RedisTemplate
public class TestingConfigurationService {

    // NEW: Inject RedisTemplate to interact with Redis.
    private final RedisTemplate<String, String> stringRedisTemplate;

    // NEW: Define a constant key for the Redis Set that will store failing IDs.
    private static final String DLT_FAILURE_IDS_KEY = "dlt-failure:broadcast-ids";
    
    // NEW: Define a constant key for the "armed" flag.
    private static final String DLT_ARMED_KEY = "dlt-failure:armed";

    /**
     * Called by the controller to arm or disarm the failure test.
     * This now sets a key in Redis.
     */
    public void setArm(boolean shouldArm) {
        if (shouldArm) {
            stringRedisTemplate.opsForValue().set(DLT_ARMED_KEY, "true");
        } else {
            stringRedisTemplate.delete(DLT_ARMED_KEY);
        }
    }

    /**
     * Called by the controller to check the current armed state for the UI.
     * This now checks for the key's existence in Redis.
     */
    public boolean isArmed() {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(DLT_ARMED_KEY));
    }

    /**
     * Called by the broadcast creation service. It atomically checks if the system
     * is armed and, if so, consumes the state by deleting the key.
     * @return true if the system was armed.
     */
    public boolean consumeArmedState() {
        // getAndDelete is an atomic operation.
        return stringRedisTemplate.opsForValue().getAndDelete(DLT_ARMED_KEY) != null;
    }

    /**
     * Marks a specific broadcast ID for failure simulation in Redis.
     */
    public void markBroadcastForFailure(Long broadcastId) {
        if (broadcastId != null) {
            stringRedisTemplate.opsForSet().add(DLT_FAILURE_IDS_KEY, String.valueOf(broadcastId));
        }
    }

    /**
     * Checks if a given broadcast ID is marked for failure by checking the Redis Set.
     */
    public boolean isMarkedForFailure(Long broadcastId) {
        if (broadcastId == null) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(DLT_FAILURE_IDS_KEY, String.valueOf(broadcastId)));
    }

    /**
     * Clears the failure mark for a broadcast ID from the Redis Set.
     */
    public void clearFailureMark(Long broadcastId) {
        if (broadcastId != null) {
            stringRedisTemplate.opsForSet().remove(DLT_FAILURE_IDS_KEY, String.valueOf(broadcastId));
        }
    }
}