package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import com.example.broadcast.shared.service.BroadcastStatisticsService;
import com.example.broadcast.shared.service.TestingConfigurationService;
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
    private final TestingConfigurationService testingConfigurationService;

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

        if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
            log.warn("DLT TEST MODE: Simulating failure in Orchestrator for broadcast ID: {}", event.getBroadcastId());
            throw new RuntimeException("Simulating DLT failure in Orchestrator for broadcast ID: " + event.getBroadcastId());
        }

        log.info("Orchestration event received for type '{}' on broadcast ID: {}. [Topic: {}, Partition: {}, Offset: {}]", event.getEventType(), event.getBroadcastId(), topic, partition, offset);
        BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId()).orElse(null);

        if (broadcast == null) {
            log.error("Cannot orchestrate event. BroadcastMessage with ID {} not found. Acknowledging to avoid retry.", event.getBroadcastId());
            acknowledgment.acknowledge();
            return;
        }

        // --- REFACTORED LOGIC WITH SWITCH STATEMENT ---
        switch (Constants.EventType.valueOf(event.getEventType())) {
            case CREATED:
            case CANCELLED:
            case EXPIRED:
                handleBroadcastLifecycleEvent(broadcast, event);
                break;
            case CACHE_EVICT_BROADCAST:
                log.info("Processing cache eviction from broadcast content ID: {}", event.getBroadcastId());
                cacheService.evictBroadcastContent(event.getBroadcastId());
            case CACHE_EVICT_ACTIVEGROUP:
                log.info("Processing cache eviction from active group broadcast ID: {}", event.getBroadcastId());
                cacheService.evictActiveGroupBroadcastsCache();
            case CACHE_EVICT_BROADCAST_ACTIVEGROUP:
                log.info("Processing cache eviction to activegroup and broadcast content ID: {}", event.getBroadcastId());
                cacheService.evictActiveGroupBroadcastsCache();
                cacheService.evictBroadcastContent(event.getBroadcastId());
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
        Map<String, UserConnectionInfo> userConnections = cacheService.getConnectionsForUser(userId);

        if (!userConnections.isEmpty()) {
            UserConnectionInfo connectionInfo = userConnections.values().iterator().next();
            String uniquePodId = connectionInfo.getClusterName() + ":" + connectionInfo.getPodId();
            
            String messageKey = UUID.randomUUID().toString();
            GeodeSsePayload payload = new GeodeSsePayload(uniquePodId, userSpecificEvent);

            sseMessagesRegion.put(messageKey, payload);
        } else {
            // Only cache CREATED events for offline users. Do not cache CANCEL/EXPIRE/READ events.
            if (Constants.EventType.CREATED.name().equals(userSpecificEvent.getEventType())) {
                log.debug("User {} is offline. Caching pending CREATED event for broadcast {}.", userId, userSpecificEvent.getBroadcastId());
                cacheService.cachePendingEvent(userSpecificEvent);
            }
        }
    }

    private List<String> determineTargetUsers(BroadcastMessage broadcast) {
        String targetType = broadcast.getTargetType();
        
        if (Constants.TargetType.PRODUCT.name().equals(targetType)) {
            log.debug("Distributing for fan-out-on-write broadcast ({}). Reading from broadcast_user_targets table.", targetType);
            return userBroadcastTargetRepository.findUserIdsByBroadcastId(broadcast.getId());
        }
        
        log.debug("Distributing for fan-out-on-read broadcast ({}).", targetType);
        if (Constants.TargetType.ALL.name().equals(targetType)) {
            return userService.getAllUserIds();
        }
        if (Constants.TargetType.ROLE.name().equals(targetType)) {
            return broadcast.getTargetIds().stream()
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
        }
        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            return broadcast.getTargetIds();
        }
        return Collections.emptyList();
    }
}