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

import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaOrchestratorConsumerService {

    private final CacheService cacheService;
    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastMapper broadcastMapper;
    
    @Qualifier("sseUserMessagesRegion")
    private final Region<String, Object> sseUserMessagesRegion;

    @Qualifier("sseAllMessagesRegion")
    private final Region<String, Object> sseAllMessagesRegion;

    @KafkaListener(
            topics = "#{@topicNamer.getOrchestrationTopic()}",
            groupId = "${broadcast.kafka.consumer.group-orchestration}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void orchestrateBroadcastEvents(MessageDeliveryEvent event,
                                           @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                           @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                           @Header(KafkaHeaders.OFFSET) long offset,
                                           Acknowledgment acknowledgment) {

        if (event.getUserId() != null) {
            handleUserSpecificEvent(event);

            if (event.isFireAndForget()) {
                userBroadcastRepository.findByUserIdAndBroadcastId(event.getUserId(), event.getBroadcastId())
                    .ifPresent(message -> {
                        log.info("Marking Fire-and-Forget message as read in DB for user {} and broadcast {}", event.getUserId(), event.getBroadcastId());
                        userBroadcastRepository.markAsRead(message.getId(), OffsetDateTime.now(ZoneOffset.UTC));
                    });
                cacheService.evictUserInbox(event.getUserId());
            }
        } else {
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
        
        // Instead of looping through all users, publish one generic event.
        log.info("Scattering generic 'ALL' type broadcast {} to all pods via Geode.", broadcast.getId());
        String messageKey = UUID.randomUUID().toString();
        // The event in the payload intentionally has a null userId.
        GeodeSsePayload payload = new GeodeSsePayload(Constants.GeodeRegionNames.SSE_ALL_MESSAGES, event);
        sseAllMessagesRegion.put(messageKey, payload);
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

            sseUserMessagesRegion.put(messageKey, payload);
        } else {
            log.trace("UserID {} is Offline.", userId);
        }
    }
}