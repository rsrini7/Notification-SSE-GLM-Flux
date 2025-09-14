package com.example.broadcast.admin.service;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.service.BroadcastStatisticsService;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.exception.UserServiceUnavailableException;

import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.JsonUtils;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.ThreadLocalRandom;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
@Monitored("service")
public class BroadcastTargetingService {

    private final UserService userService;
    private final BroadcastRepository broadcastRepository;
    private final AppProperties appProperties;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsService broadcastStatisticsService;

    /**
     * Asynchronously fetches the complete user list for a broadcast, stores it,
     * and updates the broadcast status to READY or FAILED.
     * @param broadcastId The ID of the broadcast to process.
     */
    @Async
    @Transactional
    public void precomputeAndStoreTargetUsers(Long broadcastId) {
        try {
            BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new IllegalStateException("Broadcast not found for pre-computation: " + broadcastId));

            // 1. Simulate the long-running user fetch
            log.info("Starting long-running user fetch for broadcast ID: {}", broadcastId);
            List<String> targetUserIds = determineAllTargetedUsers(broadcast);
            log.info("Fetch complete. Found {} target users for broadcast ID: {}", targetUserIds.size(), broadcastId);

            // 2. Persist these users directly as PENDING messages
            if (!targetUserIds.isEmpty()) {
                 List<UserBroadcastMessage> userMessages = targetUserIds.stream()
                    .map(userId -> UserBroadcastMessage.builder()
                            .broadcastId(broadcast.getId())
                            .userId(userId)
                            .deliveryStatus(Constants.DeliveryStatus.PENDING.name())
                            .readStatus(Constants.ReadStatus.UNREAD.name())
                            .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                            .build())
                    .collect(Collectors.toList());
                userBroadcastRepository.saveAll(userMessages);
                broadcastStatisticsService.initializeStatistics(broadcastId, targetUserIds.size());
            }

            // 3. Mark the broadcast as READY for activation by the scheduler
            broadcastRepository.updateStatus(broadcastId, Constants.BroadcastStatus.READY.name());
            log.info("Successfully pre-computed and stored {} user messages for broadcast ID: {}. Status is now READY.", targetUserIds.size(), broadcastId);

        } catch (Exception e) {
            log.error("Failed to pre-compute users for broadcast ID: {}. Setting status to FAILED.", broadcastId, e);
            broadcastRepository.updateStatus(broadcastId, Constants.BroadcastStatus.FAILED.name());
        }
    }
    
    /**
     * Determines the target user list based on the broadcast's target type.
     * This is the method that can take up to 15 minutes.
     * @param broadcast The broadcast message object.
     * @return A list of user IDs.
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackDetermineUsers")
    @Bulkhead(name = "userService")
    private List<String> determineAllTargetedUsers(BroadcastMessage broadcast) {
        
        // SIMULATION :: User List Preparation
        long maxDelay = appProperties.getSimulation().getUserFetchDelayMs();
        if (maxDelay > 0) {
            long randomDelay = ThreadLocalRandom.current().nextLong(maxDelay + 1); // +1 to make the max inclusive
            log.info("SIMULATION: Starting a random delay up to {}ms... (Actual: {}ms)", maxDelay, randomDelay);
            try{
                Thread.sleep(randomDelay);
            }catch(InterruptedException e){
                log.warn("Suppresing Thread Sleep exception {}", e.getMessage());
            }
            log.info("SIMULATION: Delay finished.");
        }else{
            log.info("SIMULATION: disabld. Simulation delay is 0");
        }
        
        String targetType = broadcast.getTargetType();

         if (Constants.TargetType.PRODUCT.name().equals(targetType)) {
            return JsonUtils.parseJsonArray(broadcast.getTargetIds()).stream()
                .flatMap(productId -> userService.getUserIdsByProduct(productId).stream())
                .distinct()
                .collect(Collectors.toList());
        }

        log.warn("determineAllTargetedUsers was called for an unexpected type: {}. This service should only handle PRODUCT broadcasts. Returning empty list.", targetType);
        return Collections.emptyList();
    }
    
    public List<String> fallbackDetermineUsers(BroadcastMessage broadcast, Throwable t) {
        log.error("Circuit breaker opened for userService. Falling back for broadcast ID {}. Error: {}", broadcast.getId(), t.getMessage());
        throw new UserServiceUnavailableException("User service is unavailable, cannot determine target users.", t);
    }
}