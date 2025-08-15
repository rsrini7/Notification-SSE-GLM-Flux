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
import com.example.broadcast.shared.service.TestingConfigurationService;
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
import java.util.Optional;
import java.util.stream.Collectors;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class KafkaConsumerService {

    // Add this field to record the service's startup time
    private final ZonedDateTime startupTime = ZonedDateTime.now(ZoneOffset.UTC);


    private final SseService sseService;
    private final CacheService cacheService;
    private final UserService userService;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final TestingConfigurationService testingConfigurationService;

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name.selected:broadcast-events-selected}",
            groupId = "${broadcast.kafka.consumer.selected-group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void processSelectedUsersBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {
        
        // If the event is older than the pod, ignore it.
        if (event.getTimestamp().isBefore(this.startupTime)) {
            log.trace("Skipping old message from pod restart. Event ID: {}", event.getEventId());
            acknowledgment.acknowledge(); // Acknowledge to advance offset
            return;
        }

        if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
            log.warn("DLT TEST MODE [SELECTED]: Simulating failure for broadcast ID: {}", event.getBroadcastId());
            throw new RuntimeException("Simulating DLT failure for broadcast ID: " + event.getBroadcastId());
        }

        try {
            handleBroadcastCreated(event);
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process message for selected user. Root cause: {}", e.getMessage());
            throw new MessageProcessingException("Failed to process message", e, event);
        }
    }
    
    @KafkaListener(
        topics = "${broadcast.kafka.topic.name.user.group:broadcast-user-events-group}",
        groupId = "${broadcast.kafka.consumer.group-user-group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void processGroupBroadcastEvent(
            @Payload MessageDeliveryEvent event,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) String partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        // If the event is older than the pod, ignore it.
        if (event.getTimestamp().isBefore(this.startupTime)) {
            log.trace("Skipping old message from pod restart. Event ID: {}", event.getEventId());
            acknowledgment.acknowledge(); // Acknowledge to advance offset
            return;
        }

        try {
            if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
                log.warn("DLT TEST MODE [GROUP]: Simulating failure for broadcast ID: {}", event.getBroadcastId());
                throw new RuntimeException("Simulating DLT failure for broadcast ID: " + event.getBroadcastId());
            }

            log.info("Received group broadcast event for broadcast ID: {}", event.getBroadcastId());
            
            Optional<BroadcastMessage> broadcastOpt = broadcastRepository.findById(event.getBroadcastId());
            if (broadcastOpt.isEmpty()) {
                log.warn("Broadcast {} was not found. It may have been cancelled or expired. Acknowledging message.", event.getBroadcastId());
                acknowledgment.acknowledge();
                return;
            }

            BroadcastMessage broadcast = broadcastOpt.get();
            List<String> onlineUsers = cacheService.getOnlineUsers();
            List<String> targetUserIds = determineAllTargetedUsers(broadcast);
            
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

     /**
     * NEW LISTENER: Dedicated consumer for all action events.
     */
    @KafkaListener(
        topics = "${broadcast.kafka.topic.name.user.actions:broadcast-user-actions}",
        groupId = "${broadcast.kafka.consumer.actions-user-group-id}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void processActionEvent(@Payload MessageDeliveryEvent event, Acknowledgment acknowledgment) {
        log.debug("Processing ACTION event: {} for broadcast: {}", event.getEventType(), event.getBroadcastId());
        try {
            EventType eventType = EventType.valueOf(event.getEventType());
            switch (eventType) {
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
                    log.warn("Unhandled event type on actions topic: {}", event.getEventType());
            }
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process action event. Root cause: {}", e.getMessage(), e);
            throw new MessageProcessingException("Failed to process action event", e, event);
        }
    }

    /**
     * NEW HELPER METHOD: Centralizes the logic for determining all users targeted by a group broadcast.
     */
    private List<String> determineAllTargetedUsers(BroadcastMessage broadcast) {
        if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
            return userService.getAllUserIds();
        } else if (Constants.TargetType.ROLE.name().equals(broadcast.getTargetType())) {
            return broadcast.getTargetIds().stream()
                .flatMap(role -> userService.getUserIdsByRole(role).stream())
                .distinct()
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
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