package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.mapper.SharedEventMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.dto.GeodeSsePayload;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.user.service.cache.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.slf4j.MDC;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaOrchestratorConsumerService {

    private final CacheService cacheService;
    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final SharedEventMapper sharedEventMapper;
    
    @Qualifier("sseUserMessagesRegion")
    private final Region<String, Object> sseUserMessagesRegion;

    @Qualifier("sseGroupMessagesRegion")
    private final Region<String, Object> sseGroupMessagesRegion;

    @Monitored("kafka-consumer")
    @KafkaListener(
            topics = "#{@kafkaListnerHelper.getOrchestrationTopic()}",
            groupId = "#{@kafkaListnerHelper.getOrchestrationGroupId()}",
            containerFactory = "#{@kafkaListnerHelper.getOrchestratorListnerContainerFactory()}"
    )
    @Transactional
    public void orchestrateBroadcastEvents(MessageDeliveryEvent event,
                                           @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
                                           @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
                                           @Header(KafkaHeaders.OFFSET) long offset,
                                           Acknowledgment acknowledgment) {

        // 1. Get the correlation_id from the event payload and set the MDC
        if (event.getCorrelationId() != null) {
            MDC.put(Constants.CORRELATION_ID, event.getCorrelationId());
        }                                            

        try {
            cacheService.getBroadcastContent(event.getBroadcastId()).or(() ->
                broadcastRepository.findById(event.getBroadcastId())
                    .map(sharedEventMapper::toBroadcastContentDTO)
                    .map(content -> {
                        cacheService.cacheBroadcastContent(content);
                        log.info("Proactively cached content for broadcast {}", event.getBroadcastId());
                        return content;
                    })
            );

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
                handleGroupLevelEvent(event);
            }

            acknowledgment.acknowledge();
         } finally {
            // 2. CRITICAL: Always clear the MDC to prevent it from leaking to other messages
            MDC.remove(Constants.CORRELATION_ID);
        }
    }

    private void handleUserSpecificEvent(MessageDeliveryEvent event) {
        log.debug("Processing user-specific event for user {}", event.getUserId());
        if (Constants.EventType.valueOf(event.getEventType()) == Constants.EventType.CREATED) {
            log.info("Evicting inbox cache for user {} due to new CREATED event.", event.getUserId());
            cacheService.evictUserInbox(event.getUserId());
        }
        scatterToUser(event);
    }

    /**
     * Handles group-level events that don't have a specific userId.
     * This includes new 'ALL' broadcasts and CANCEL/EXPIRE events for ANY broadcast type.
     */
    private void handleGroupLevelEvent(MessageDeliveryEvent event) {
        BroadcastMessage broadcast = broadcastRepository.findById(event.getBroadcastId()).orElse(null);
        if (broadcast == null) {
            log.error("BroadcastMessage {} not found for 'ALL' broadcast.", event.getBroadcastId());
            return;
        }
        
        switch (Constants.EventType.valueOf(event.getEventType())) {
            case CREATED:
                cacheService.cacheBroadcastContent(sharedEventMapper.toBroadcastContentDTO(broadcast));
                List<String> onlineUsers = cacheService.getOnlineUsers();
                if (!onlineUsers.isEmpty()) {
                    log.info("Updating statistics for 'ALL' broadcast {}: targeting and delivering to {} online users.", broadcast.getId(), onlineUsers.size());
                    broadcastStatisticsRepository.incrementDeliveredCount(broadcast.getId(), onlineUsers.size());
                }
                break;
            case CANCELLED:
            case EXPIRED:
                log.info("Processing cancellation/expiration for broadcast ID: {}. Evicting caches.", broadcast.getId());
                cacheService.evictBroadcastContent(broadcast.getId());

                if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
                    // For an 'ALL' broadcast, we must evict the inbox for all *online* users,
                    // as they are the only ones who could have a cached entry for it.
                    List<String> onlineUsersForEvict = cacheService.getOnlineUsers();
                    for (String userId : onlineUsersForEvict) {
                        cacheService.evictUserInbox(userId);
                    }
                    log.info("Evicted inbox caches for {} online users due to 'ALL' broadcast cancellation.", onlineUsersForEvict.size());
                } else {
                    // For targeted broadcasts (ROLE, SELECTED, etc.), we can be more precise.
                    List<UserBroadcastMessage> affectedUsers = userBroadcastRepository.findByBroadcastId(broadcast.getId());
                    for (UserBroadcastMessage userMessage : affectedUsers) {
                        cacheService.evictUserInbox(userMessage.getUserId());
                    }
                    log.info("Evicted user inbox caches for {} users affected by targeted broadcast cancellation.", affectedUsers.size());
                }
                break;
            case READ:
                handleReadEvent(event);
                break;
            default:
                log.warn("Received an unexpected group-level event type '{}' for an 'ALL' broadcast. Ignoring cache logic.", event.getEventType());
                break;
        }
        
        // Instead of looping through all users, publish one generic event.
        log.info("Putting generic broadcast event {} into 'sse-group-messages' region.", event.getBroadcastId());
        String messageKey = UUID.randomUUID().toString();
        // Put the raw event, as the region itself is the broadcast target.
        sseGroupMessagesRegion.put(messageKey, event);
    }

     private void handleReadEvent(MessageDeliveryEvent event) {
        log.info("Scattering single '{}' event to Geode Region for user {}", event.getEventType(), event.getUserId());
        scatterToUser(event);
    }
    
    private void scatterToUser(MessageDeliveryEvent userSpecificEvent) {
        String userId = userSpecificEvent.getUserId();

        Map<String, UserConnectionInfo> userConnections = cacheService.getConnectionsForUser(userId);

        if (userConnections != null && !userConnections.isEmpty()) {
            UserConnectionInfo connectionInfo = userConnections.values().iterator().next();
            String uniqueClusterPodName = connectionInfo.getClusterName() + ":" + connectionInfo.getPodName();
            
            String messageKey = UUID.randomUUID().toString();
            GeodeSsePayload payload = new GeodeSsePayload(uniqueClusterPodName, userSpecificEvent);

            sseUserMessagesRegion.put(messageKey, payload);
            log.debug("Put user-specific event for user {} into 'sse-user-messages' region, targeting pod {}", userId, uniqueClusterPodName);
        } else {
            log.trace("UserID {} is Offline.", userId);
        }
    }
}