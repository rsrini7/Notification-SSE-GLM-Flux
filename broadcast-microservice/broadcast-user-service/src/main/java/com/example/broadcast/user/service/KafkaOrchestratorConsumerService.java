package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import com.example.broadcast.shared.service.BroadcastStatisticsService;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.dto.GeodeSsePayload;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Optional;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaOrchestratorConsumerService {

    private final UserBroadcastTargetRepository userBroadcastTargetRepository;
    private final CacheService cacheService;
    private final BroadcastRepository broadcastRepository;
    private final UserService userService;
    private final BroadcastStatisticsService broadcastStatisticsService;
    private final AppProperties appProperties;
    
    @Qualifier("sseMessagesRegion")
    private final Region<String, Object> sseMessagesRegion;

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name-orchestration}",
            groupId = "${broadcast.kafka.consumer.group-orchestration}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void orchestrateBroadcastEvents(MessageDeliveryEvent event,
                                           @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                           @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                           @Header(KafkaHeaders.OFFSET) long offset,
                                           Acknowledgment acknowledgment) {

        log.info("Orchestration event received for type '{}' on broadcast ID: {}. [Topic: {}, Partition: {}, Offset: {}]", event.getEventType(), event.getBroadcastId(), topic, partition, offset);
        
        // Route user-specific events directly
        if (Constants.EventType.TARGETS_PRECOMPUTED.name().equals(event.getEventType())) {
            handleTargetsPrecomputedEvent(event);
            acknowledgment.acknowledge();
            return;
        }
        
        BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId()).orElse(null);

        if (broadcast == null) {
            log.error("Cannot orchestrate event. BroadcastMessage with ID {} not found. Acknowledging to avoid retry.", event.getBroadcastId());
            acknowledgment.acknowledge();
            return;
        }

        switch (Constants.EventType.valueOf(event.getEventType())) {
            case CREATED:
                // Cache the content of the new broadcast
                cacheService.cacheBroadcastContent(broadcast);
                // Surgically add this new broadcast to the active group caches
                updateActiveGroupCaches(broadcast, true);
                handleBroadcastLifecycleEvent(broadcast, event);
                break;
            case CANCELLED:
            case EXPIRED:
                // Evict the content of the specific broadcast that is no longer active
                cacheService.evictBroadcastContent(event.getBroadcastId());
                // Surgically remove this broadcast from the active group caches
                updateActiveGroupCaches(broadcast, false);
                handleBroadcastLifecycleEvent(broadcast, event);
                break;
            case FAILED:
                log.warn("Broadcast Failed.");
                break;
            case READ:
                handleReadEvent(event);
                break;
            default:
                log.warn("Unhandled event type in orchestrator: {}", event.getEventType());
                break;
        }

        acknowledgment.acknowledge();
    }

    private void handleTargetsPrecomputedEvent(MessageDeliveryEvent event) {
        Long broadcastId = event.getBroadcastId();
        log.info("Processing TARGETS_PRECOMPUTED event for broadcast ID: {}", broadcastId);
        try {
            List<String> targetUserIds = userBroadcastTargetRepository.findUserIdsByBroadcastId(broadcastId);
            if (targetUserIds.isEmpty()) {
                log.warn("No pre-computed targets found in DB for broadcast ID: {}. Cache will not be populated.", broadcastId);
                return;
            }
            cacheService.cachePrecomputedTargets(broadcastId, targetUserIds);
        } catch (Exception e) {
            log.error("Failed to process TARGETS_PRECOMPUTED event for broadcast ID: {}. The cache may not be populated.", broadcastId, e);
        }
    }

    /**
     * Surgically updates the 'active-group-broadcasts' cache by either adding or removing a single broadcast.
     * @param broadcast The broadcast to add or remove.
     * @param isAddition True to add, false to remove.
     */
    private void updateActiveGroupCaches(BroadcastMessage broadcast, boolean isAddition) {
        String targetType = broadcast.getTargetType();
        if (Constants.TargetType.ALL.name().equals(targetType)) {
            updateCacheForKey("ALL", broadcast, isAddition);
        } else if (Constants.TargetType.ROLE.name().equals(targetType)) {
            broadcast.getTargetIds().forEach(role -> {
                String cacheKey = "ROLE:" + role;
                updateCacheForKey(cacheKey, broadcast, isAddition);
            });
        }
    }

    private void updateCacheForKey(String cacheKey, BroadcastMessage broadcast, boolean isAddition) {
        List<BroadcastMessage> cachedList = cacheService.getActiveGroupBroadcasts(cacheKey);
        // Initialize the list if it doesn't exist in the cache
        if (cachedList == null) {
            cachedList = new ArrayList<>();
        }

        // Remove any existing instance of this broadcast to prevent duplicates
        cachedList.removeIf(b -> b.getId().equals(broadcast.getId()));

        if (isAddition) {
            cachedList.add(broadcast);
            log.info("Added broadcast {} to active cache for key '{}'.", broadcast.getId(), cacheKey);
        } else {
            log.info("Removed broadcast {} from active cache for key '{}'.", broadcast.getId(), cacheKey);
        }

        // Put the modified list back into the cache
        cacheService.cacheActiveGroupBroadcasts(cacheKey, cachedList);
    }

    private void handleBroadcastLifecycleEvent(BroadcastMessage broadcast, MessageDeliveryEvent event) {
        final List<String> targetUsers = determineTargetUsers(broadcast);
        if (targetUsers.isEmpty()) {
            log.warn("Orchestrator found no target users for broadcast ID {}. No events will be scattered.", broadcast.getId());
            return;
        }

        if (Constants.EventType.CREATED.name().equals(event.getEventType()) &&
                (!Constants.TargetType.PRODUCT.name().equals(broadcast.getTargetType()))) {
            broadcastStatisticsService.initializeStatistics(broadcast.getId(), targetUsers.size());
        }

        log.info("Scattering {} user-specific '{}' events to Geode Region for broadcast ID {}", targetUsers.size(), event.getEventType(), broadcast.getId());
        for (String userId : targetUsers) {
            scatterToUser(event.toBuilder().userId(userId).build());
        }
    }

    private void handleReadEvent(MessageDeliveryEvent event) {
        log.info("Scattering single '{}' event to Geode Region for user {}", event.getEventType(), event.getUserId());
        scatterToUser(event);
    }
    
    private void scatterToUser(MessageDeliveryEvent userSpecificEvent) {
        String userId = userSpecificEvent.getUserId();
        String clusterName = appProperties.getClusterName();
        
        Map<String, UserConnectionInfo> userConnections = cacheService.getConnectionsForUser(userId, clusterName);

        if (!userConnections.isEmpty()) {
            UserConnectionInfo connectionInfo = userConnections.values().iterator().next();
            String uniqueClusterPodName = connectionInfo.getClusterName() + ":" + connectionInfo.getPodName();
            
            String messageKey = UUID.randomUUID().toString();
            GeodeSsePayload payload = new GeodeSsePayload(uniqueClusterPodName, userSpecificEvent);

            sseMessagesRegion.put(messageKey, payload);
        } else {
            String podName = appProperties.getPodName();
            
            switch (Constants.EventType.valueOf(userSpecificEvent.getEventType())) {
                case CREATED:
                    log.debug("User {} is offline. Caching pending CREATED event for broadcast {}.", userId, userSpecificEvent.getBroadcastId());
                    cacheService.cachePendingEvent(userSpecificEvent, podName);
                    break;
                case CANCELLED:
                case EXPIRED:
                    log.info("User {} is offline. Removing pending event for cancelled/expired broadcast {}.", userId, userSpecificEvent.getBroadcastId());
                    cacheService.removePendingEvent(userId, userSpecificEvent.getBroadcastId());
                    break;
                default:
                    break;
            }
        }
    }

    private List<String> determineTargetUsers(BroadcastMessage broadcast) {
        Constants.TargetType targetTypeEnum;
        try {
            // Convert the string to our type-safe enum
            targetTypeEnum = Constants.TargetType.valueOf(broadcast.getTargetType());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown broadcast targetType '{}' for broadcast ID {}. No users will be targeted.", broadcast.getTargetType(), broadcast.getId());
            return Collections.emptyList();
        }

        // Use a modern switch expression for clarity and conciseness
        return switch (targetTypeEnum) {
            case PRODUCT -> {
                // 1. Try to get the list from the cache
                Optional<List<String>> cachedTargets = cacheService.getPrecomputedTargets(broadcast.getId());
                if (cachedTargets.isPresent()) {
                    log.info("[CACHE_HIT] Found pre-computed targets for broadcast {} in Geode.", broadcast.getId());
                    yield cachedTargets.get();
                }

                // 2. Fall back to the database if not in cache (for resilience)
                log.warn("[CACHE_MISS] Pre-computed targets for broadcast {} not found in cache. Falling back to DB.", broadcast.getId());
                yield userBroadcastTargetRepository.findUserIdsByBroadcastId(broadcast.getId());
            }
            case ALL -> userService.getAllUserIds();
            case ROLE -> broadcast.getTargetIds().stream()
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
            case SELECTED -> broadcast.getTargetIds();
        };
    }
}