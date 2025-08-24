package com.example.broadcast.admin.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import com.example.broadcast.shared.exception.UserServiceUnavailableException;
import com.example.broadcast.shared.service.cache.CacheService;

import com.example.broadcast.shared.util.Constants;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class BroadcastTargetingService {

    private final UserService userService;
    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastTargetRepository userBroadcastTargetRepository;
    private final AppProperties appProperties;
    private final CacheService cacheService;

    /**
     * Efficiently counts the number of target users for 'ALL' or 'ROLE' broadcasts without fetching all user IDs.
     * This is used for the "fan-out-on-read" strategy.
     */
    @CircuitBreaker(name = "userService", fallbackMethod = "fallbackCountTargetUsers")
    @Bulkhead(name = "userService")
    public int countTargetUsers(BroadcastMessage broadcast) {
        String targetType = broadcast.getTargetType();
        
        if (Constants.TargetType.ALL.name().equals(targetType)) {
            // In a real system, this would be a much more efficient COUNT query against the user service.
            return userService.getAllUserIds().size();
        } else if (Constants.TargetType.ROLE.name().equals(targetType)) {
            // Similarly, this would be an efficient count.
            return broadcast.getTargetIds().stream()
                .mapToInt(role -> userService.getUserIdsByRole(role).size())
                .sum();
        }
        
        return 0;
    }
    
    public List<UserBroadcastMessage> fallbackCreateUserBroadcastMessagesForBroadcast(BroadcastMessage broadcast, Throwable t) {
        log.error("Circuit breaker opened for user service. Falling back for broadcast ID {}. Error: {}", broadcast.getId(), t.getMessage());
        return Collections.emptyList();
    }
    
    public int fallbackCountTargetUsers(BroadcastMessage broadcast, Throwable t) {
        log.error("Circuit breaker opened for user service during count. Falling back for broadcast ID {}. Error: {}", broadcast.getId(), t.getMessage());
        return 0;
    }

    /**
     * Asynchronously fetches the complete user list for a broadcast, stores it,
     * and updates the broadcast status to READY or FAILED.
     * @param broadcastId The ID of the broadcast to process.
     */
    @Async
    @Transactional
    public void precomputeAndStoreTargetUsers(Long broadcastId) {
        // 1. Claim the broadcast by setting its status to PREPARING
        broadcastRepository.updateStatus(broadcastId, Constants.BroadcastStatus.PREPARING.name());

        try {
            BroadcastMessage broadcast = broadcastRepository.findById(broadcastId)
                .orElseThrow(() -> new IllegalStateException("Broadcast not found for pre-computation: " + broadcastId));

            // 2. Simulate the long-running user fetch
            log.info("Starting long-running user fetch for broadcast ID: {}", broadcastId);
            List<String> targetUserIds = determineAllTargetedUsers(broadcast);
            log.info("Fetch complete. Found {} target users for broadcast ID: {}", targetUserIds.size(), broadcastId);

            // 3. Store the results in the new table
            userBroadcastTargetRepository.batchInsert(broadcastId, targetUserIds);

            // 4. Cache the result after saving to DB
            cacheService.cachePrecomputedTargets(broadcastId, targetUserIds);

            // 5. Mark the broadcast as READY for activation
            broadcastRepository.updateStatus(broadcastId, Constants.BroadcastStatus.READY.name());
            log.info("Successfully pre-computed and stored {} users for broadcast ID: {}", targetUserIds.size(), broadcastId);

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
    public List<String> determineAllTargetedUsers(BroadcastMessage broadcast) {
        
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

        if (Constants.TargetType.ALL.name().equals(targetType)) {
            return userService.getAllUserIds();
        } else if (Constants.TargetType.ROLE.name().equals(targetType)) {
            return broadcast.getTargetIds().stream()
                .flatMap(role -> userService.getUserIdsByRole(role).stream())
                .distinct()
                .collect(Collectors.toList());
        } else if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            return broadcast.getTargetIds();
        }else if (Constants.TargetType.PRODUCT.name().equals(targetType)) {
            // Fetch users for each specified product and combine them into a unique list.
            return broadcast.getTargetIds().stream()
                .flatMap(productId -> userService.getUserIdsByProduct(productId).stream())
                .distinct()
                .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }
    
    public List<String> fallbackDetermineUsers(BroadcastMessage broadcast, Throwable t) {
        log.error("Circuit breaker opened for userService. Falling back for broadcast ID {}. Error: {}", broadcast.getId(), t.getMessage());
        throw new UserServiceUnavailableException("User service is unavailable, cannot determine target users.", t);
    }
}