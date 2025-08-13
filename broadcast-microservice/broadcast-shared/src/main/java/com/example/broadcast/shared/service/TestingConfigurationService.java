package com.example.broadcast.shared.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TestingConfigurationService {

    private final RedisTemplate<String, String> dltTestRedisTemplate;

    private static final String DLT_FAILURE_IDS_KEY = "dlt-failure:broadcast-ids";
    private static final String DLT_ARMED_KEY = "dlt-failure:armed";

    public TestingConfigurationService(@Qualifier("dltTestRedisTemplate") RedisTemplate<String, String> dltTestRedisTemplate) {
        this.dltTestRedisTemplate = dltTestRedisTemplate;
    }

    @PostConstruct
    public void checkBean() {
        // This log will run once on startup and tell us exactly what kind of bean was injected.
        log.info("[DEBUG-REDIS] Injected RedisTemplate bean of class: {}", dltTestRedisTemplate.getClass().getName());
    }

    public void setArm(boolean shouldArm) {
        log.info("[DEBUG-REDIS] Attempting to set 'armed' status to: {}", shouldArm);
        if (shouldArm) {
            dltTestRedisTemplate.opsForValue().set(DLT_ARMED_KEY, "true");
            // VERIFICATION STEP: Immediately read the key back
            String verification = dltTestRedisTemplate.opsForValue().get(DLT_ARMED_KEY);
            log.info("[DEBUG-REDIS] Read-after-write verification for 'armed' key. Result: '{}'", verification);
        } else {
            dltTestRedisTemplate.delete(DLT_ARMED_KEY);
            log.info("[DEBUG-REDIS] Deleted 'armed' key.");
        }
    }

    public boolean isArmed() {
        return Boolean.TRUE.equals(dltTestRedisTemplate.hasKey(DLT_ARMED_KEY));
    }

    public boolean consumeArmedState() {
        log.info("[DEBUG-REDIS] Attempting to consume 'armed' key...");
        boolean wasArmed = dltTestRedisTemplate.opsForValue().getAndDelete(DLT_ARMED_KEY) != null;
        log.info("[DEBUG-REDIS] Consumed 'armed' key. Was present: {}", wasArmed);
        return wasArmed;
    }

    public void markBroadcastForFailure(Long broadcastId) {
        if (broadcastId != null) {
            log.info("[DEBUG-REDIS] Attempting to mark broadcast ID {} for failure...", broadcastId);
            dltTestRedisTemplate.opsForSet().add(DLT_FAILURE_IDS_KEY, String.valueOf(broadcastId));
            // VERIFICATION STEP: Immediately check if the member was added
            boolean isMember = Boolean.TRUE.equals(dltTestRedisTemplate.opsForSet().isMember(DLT_FAILURE_IDS_KEY, String.valueOf(broadcastId)));
            log.info("[DEBUG-REDIS] Read-after-write verification for broadcast ID {}. Is member: {}", broadcastId, isMember);
        }
    }

    public boolean isMarkedForFailure(Long broadcastId) {
        if (broadcastId == null) {
            return false;
        }
        return Boolean.TRUE.equals(dltTestRedisTemplate.opsForSet().isMember(DLT_FAILURE_IDS_KEY, String.valueOf(broadcastId)));
    }

    public void clearFailureMark(Long broadcastId) {
        if (broadcastId != null) {
            dltTestRedisTemplate.opsForSet().remove(DLT_FAILURE_IDS_KEY, String.valueOf(broadcastId));
        }
    }
}