package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
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
import java.time.ZonedDateTime;
import java.time.ZoneOffset;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaOrchestratorConsumerService {

    private final CacheService cacheService;
    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastMapper broadcastMapper;
    
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

            if (event.isFireAndForget()) {
                userBroadcastRepository.findByUserIdAndBroadcastId(event.getUserId(), event.getBroadcastId())
                    .ifPresent(message -> {
                        log.info("Marking Fire-and-Forget message as read in DB for user {} and broadcast {}", event.getUserId(), event.getBroadcastId());
                        userBroadcastRepository.markAsRead(message.getId(), ZonedDateTime.now(ZoneOffset.UTC));
                    });
                cacheService.evictUserInbox(event.getUserId());
            }
        } else {
            // If no userId, it's a group event for an 'ALL' type broadcast.
            // 2. If no userId, it can only be a group event for an 'ALL' type broadcast.
            handleAllUsersBroadcast(event);
        }

        acknowledgment.acknowledge();
    }

    private void handleUserSpecificEvent(MessageDeliveryEvent event) {
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
                cacheService.cacheBroadcastContent(broadcastMapper.toBroadcastContentDTO(broadcast));
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

        cacheService.evictUserInbox(userId);

        Map<String, UserConnectionInfo> userConnections = cacheService.getConnectionsForUser(userId);

        if (userConnections != null && !userConnections.isEmpty()) {
            UserConnectionInfo connectionInfo = userConnections.values().iterator().next();
            String uniqueClusterPodName = connectionInfo.getClusterName() + ":" + connectionInfo.getPodName();
            
            String messageKey = UUID.randomUUID().toString();
            GeodeSsePayload payload = new GeodeSsePayload(uniqueClusterPodName, userSpecificEvent);

            sseMessagesRegion.put(messageKey, payload);
        } else {
            log.trace("UserID {} is Offline.", userId);
        }
    }
}