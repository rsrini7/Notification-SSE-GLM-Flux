package com.example.broadcast.shared.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@Profile("!admin-only")
public class TestingConfigurationService {

    private final Region<String, Boolean> dltArmedRegion;
    private final Region<Long, Boolean> dltFailureIdsRegion;
    
    private static final String DLT_ARMED_KEY = "DLT_SYSTEM_ARMED";

    public TestingConfigurationService(
            @Qualifier("dltArmedRegion") Region<String, Boolean> dltArmedRegion,
            @Qualifier("dltFailureIdsRegion") Region<Long, Boolean> dltFailureIdsRegion) {
        this.dltArmedRegion = dltArmedRegion;
        this.dltFailureIdsRegion = dltFailureIdsRegion;
    }

    public void setArm(boolean shouldArm) {
        if (shouldArm) {
            dltArmedRegion.put(DLT_ARMED_KEY, true);
        } else {
            dltArmedRegion.remove(DLT_ARMED_KEY);
        }
    }

    public boolean isArmed() {
        return dltArmedRegion.containsKey(DLT_ARMED_KEY);
    }

    public boolean consumeArmedState() {
        // region.remove(key, value) is an atomic check-and-remove operation.
        // It returns true only if the key was present and its value was equal to the specified value.
        return dltArmedRegion.remove(DLT_ARMED_KEY, true);
    }

    public void markBroadcastForFailure(Long broadcastId) {
        if (broadcastId != null) {
            dltFailureIdsRegion.put(broadcastId, true);
        }
    }

    public boolean isMarkedForFailure(Long broadcastId) {
        if (broadcastId == null) {
            return false;
        }
        return dltFailureIdsRegion.containsKey(broadcastId);
    }

    public void clearFailureMark(Long broadcastId) {
        if (broadcastId != null) {
            dltFailureIdsRegion.remove(broadcastId);
        }
    }
}