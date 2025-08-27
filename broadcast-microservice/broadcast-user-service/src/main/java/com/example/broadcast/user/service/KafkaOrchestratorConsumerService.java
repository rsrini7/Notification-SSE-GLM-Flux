package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.dto.GeodeSsePayload;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.user.service.cache.CacheService;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaOrchestratorConsumerService {

    private final CacheService cacheService;
    private final BroadcastRepository broadcastRepository;
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

        // 1. Handle user-specific events immediately.
        // This now covers SELECTED, ROLE, and PRODUCT broadcasts.
        if (event.getUserId() != null) {
            handleUserSpecificEvent(event);
            acknowledgment.acknowledge();
            return;
        }

        // 2. If no userId, it can only be a group event for an 'ALL' type broadcast.
        handleAllUsersBroadcast(event);
        acknowledgment.acknowledge();
    }

    private void handleUserSpecificEvent(MessageDeliveryEvent event) {
        // Cache logic is not needed here because there's no "group" to cache.
        // We just deliver the message.
        log.debug("Processing user-specific event for user {}", event.getUserId());
        scatterToUser(event);
    }

    private void handleAllUsersBroadcast(MessageDeliveryEvent event) {
        BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId()).orElse(null);
        if (broadcast == null) {
            log.error("BroadcastMessage {} not found for 'ALL' broadcast.", event.getBroadcastId());
            return;
        }
        
        // The cache logic is ONLY needed for 'ALL' type broadcasts.
        switch (Constants.EventType.valueOf(event.getEventType())) {
            case CREATED:
                cacheService.cacheBroadcastContent(broadcast);
                break;
            case CANCELLED:
            case EXPIRED:
                cacheService.evictBroadcastContent(broadcast.getId());
                break;
            case READ:
                handleReadEvent(event);
                break;
            default:
                log.warn("Received an unexpected group-level event type '{}' for an 'ALL' broadcast. Ignoring cache logic.", event.getEventType());
                break;
        }
        
        // Fan out to all users
        List<String> onlineUsers = cacheService.getOnlineUsers();
        if (onlineUsers.isEmpty()) {
            log.info("No users are currently online to receive the 'ALL' broadcast {}.", broadcast.getId());
            return;
        }

        log.info("Scattering 'ALL' type broadcast {} to {} online users.", broadcast.getId(), onlineUsers.size());
        for (String userId : onlineUsers) {
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
}