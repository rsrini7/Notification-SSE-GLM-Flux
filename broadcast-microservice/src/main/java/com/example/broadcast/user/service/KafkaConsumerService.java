package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.exception.MessageProcessingException;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.EventType;
import com.example.broadcast.admin.service.TestingConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    private final SseService sseService;
    private final CacheService cacheService;
    private final UserService userService;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final TestingConfigurationService testingConfigurationService;

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name.selected:broadcast-events-selected}",
            groupId = "${spring.kafka.consumer.group-id:broadcast-service-group}-selected",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processSelectedUsersBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
            log.warn("DLT TEST MODE [SELECTED]: Simulating failure for broadcast ID: {}", event.getBroadcastId());
            throw new RuntimeException("Simulating DLT failure for broadcast ID: " + event.getBroadcastId());
        }

        processBroadcastEvent(event, topic, partition, offset, acknowledgment);
    }
    
    @KafkaListener(
        topics = "${broadcast.kafka.topic.name.group:broadcast-events-group}",
        groupId = "${spring.kafka.consumer.group-id:broadcast-service-group}-group",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void processGroupBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        try {
            if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
                log.warn("DLT TEST MODE [GROUP]: Simulating failure for broadcast ID: {}", event.getBroadcastId());
                throw new RuntimeException("Simulating DLT failure for broadcast ID: " + event.getBroadcastId());
            }

            log.info("Received group broadcast event for broadcast ID: {}", event.getBroadcastId());

            if (event.getEventType().equals(EventType.CANCELLED.name()) || event.getEventType().equals(EventType.EXPIRED.name())) {
                List<String> onlineUsers = cacheService.getOnlineUsers();
                log.info("Fanning out group lifecycle event {} for broadcast {} to {} online users.", event.getEventType(), event.getBroadcastId(), onlineUsers.size());
                for (String userId : onlineUsers) {
                    MessageDeliveryEvent userEvent = event.toBuilder().userId(userId).build();
                    handleEvent(userEvent); // This will route to SseService to push a 'MESSAGE_REMOVED' event
                }
                acknowledgment.acknowledge();
                return; // Exit early
            }

            // 2. Synchronously update the cache to prevent race conditions for new connections.
            List<BroadcastMessage> allActiveGroupBroadcasts = broadcastRepository.findActiveBroadcastsByTargetType("ALL");
            cacheService.cacheActiveGroupBroadcasts("ALL", allActiveGroupBroadcasts);
            log.info("Synchronously updated the active 'ALL' user broadcast cache with {} items.", allActiveGroupBroadcasts.size());
            
            // Find the specific broadcast from the fresh list to deliver to currently online users.
            BroadcastMessage broadcast = allActiveGroupBroadcasts.stream()
                .filter(b -> b.getId().equals(event.getBroadcastId()))
                .findFirst()
                .orElse(null);
            
            if (broadcast == null) {
                log.warn("Broadcast {} was not found in the active list after cache update. It may have been cancelled or expired. Acknowledging message.", event.getBroadcastId());
                acknowledgment.acknowledge();
                return;
            }

            // Determine target users (this part is unchanged)
            List<String> targetUserIds;
            if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
                targetUserIds = userService.getAllUserIds();
            } else if (Constants.TargetType.ROLE.name().equals(broadcast.getTargetType())) {
                targetUserIds = broadcast.getTargetIds().stream()
                    .flatMap(role -> userService.getUserIdsByRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
            } else {
                targetUserIds = Collections.emptyList();
            }
            
            // Deliver to online users and update stats (this part is unchanged)
            List<String> onlineUsers = cacheService.getOnlineUsers();
            List<String> usersToNotify = targetUserIds.stream()
                .filter(onlineUsers::contains)
                .collect(Collectors.toList());
            
            log.info("Delivering group broadcast {} to {} online users out of {} total targets.", event.getBroadcastId(), usersToNotify.size(), targetUserIds.size());
            for (String userId : usersToNotify) {
                MessageDeliveryEvent userEvent = event.toBuilder().userId(userId).build();
                sseService.deliverGroupBroadcastFromEvent(userEvent);
            }

            if (!usersToNotify.isEmpty()) {
                int deliveredCount = usersToNotify.size();
                broadcastStatisticsRepository.incrementDeliveredCountBy(event.getBroadcastId(), deliveredCount);
                log.info("Batch updated delivery statistics for broadcast {}: incremented delivered count by {}", event.getBroadcastId(), deliveredCount);
            }

            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process group message from topic {}. Root cause: {}", topic, e.getMessage(), e);
            throw new MessageProcessingException("Failed to process group message", e, event);
        }
    }

    private void processBroadcastEvent(
            MessageDeliveryEvent event,
            String topic,
            String partition,
            long offset,
            Acknowledgment acknowledgment) {
        
        try {
            log.debug("Processing Kafka event: {} from topic: {}, partition: {}, offset: {}",
                    event.getEventId(), topic, partition, offset);

            handleEvent(event);
            acknowledgment.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process message from topic {}. Root cause: {}", topic, e.getMessage());
            throw new MessageProcessingException("Failed to process message", e, event);
        }
    }

    private void handleEvent(MessageDeliveryEvent event) {
        EventType eventType;
        try {
            eventType = EventType.valueOf(event.getEventType());
        } catch (Exception e) {
            log.warn("Unknown or null event type: {}", event.getEventType());
            throw new IllegalArgumentException("Invalid event type received", e);
        }

        switch (eventType) {
            case CREATED:
                handleBroadcastCreated(event);
                break;
            case READ:
                handleMessageRead(event);
                break;
            case CANCELLED:
                handleBroadcastCancelled(event);
                break;
            case EXPIRED:
                handleBroadcastExpired(event);
                break;
            default:
                log.warn("Unhandled event type: {}", event.getEventType());
        }
    }

    private void handleBroadcastCreated(MessageDeliveryEvent event) {
        log.info("Handling broadcast created event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        boolean isOnline = cacheService.isUserOnline(event.getUserId()) || sseService.isUserConnected(event.getUserId());
        if (isOnline) {
            sseService.handleMessageEvent(event);
            log.info("Broadcast event for online user {} forwarded to SSE service.", event.getUserId());
        } else if (!event.isFireAndForget()) {
            log.info("User {} is offline, message remains pending", event.getUserId());
            cacheService.cachePendingEvent(event);
        }
    }
    
    private void handleMessageRead(MessageDeliveryEvent event) {
        log.info("Handling message read event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
        cacheService.removeMessageFromUserCache(event.getUserId(), event.getBroadcastId());
    }

    private void handleBroadcastCancelled(MessageDeliveryEvent event) {
        log.info("Handling broadcast cancelled event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        cacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
        cacheService.removeMessageFromUserCache(event.getUserId(), event.getBroadcastId());
        log.debug("Removed cancelled message from pending and active caches for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }

    private void handleBroadcastExpired(MessageDeliveryEvent event) {
        log.info("Handling broadcast expired event for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        cacheService.removePendingEvent(event.getUserId(), event.getBroadcastId());
        cacheService.removeMessageFromUserCache(event.getUserId(), event.getBroadcastId());
        log.debug("Removed expired message from pending and active caches for user: {}, broadcast: {}", event.getUserId(), event.getBroadcastId());
        sseService.handleMessageEvent(event);
    }
}