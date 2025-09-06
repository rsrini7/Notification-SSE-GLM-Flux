package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.SseEventType;
import com.example.broadcast.user.service.cache.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SseService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository; 
    private final UserMessageService userMessageService;
    private final BroadcastMapper broadcastMapper;
    private final SseConnectionManager sseConnectionManager;
    private final SseEventFactory sseEventFactory;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService;

    @Transactional
    public void registerConnection(String userId, String connectionId) {
        sseConnectionManager.registerConnection(userId, connectionId);
    }

    public Flux<ServerSentEvent<String>> createEventStream(String userId, String connectionId) {
        log.info("Establishing LIVE event stream for user: {}, connection: {}", userId, connectionId);
        // It no longer fetches historical messages. It just returns the live connection sink.
        return sseConnectionManager.createEventStream(userId, connectionId);
    }

    @Transactional
    public void removeEventStream(String userId, String connectionId) {
        sseConnectionManager.removeEventStream(userId, connectionId);
    }

    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Orchestrating message event: {} for user: {}", event.getEventType(), event.getUserId());
        switch (Constants.EventType.valueOf(event.getEventType())) {
            case CREATED:
                broadcastRepository.findById(event.getBroadcastId()).ifPresentOrElse(
                    broadcast -> {
                        if (Constants.TargetType.ALL.name().equals(broadcast.getTargetType())) {
                            deliverFanOutOnReadMessage(event.getUserId(), broadcast);
                        } else {
                            deliverTargetedMessage(event.getUserId(), broadcast);
                        }
                    },
                    () -> log.error("Cannot process CREATED event. BroadcastMessage with ID {} not found.", event.getBroadcastId())
                );
                break;
            case CANCELLED:
            case EXPIRED:
                sendRemoveMessageEvent(event.getUserId(), event.getBroadcastId());
                break;
            case READ:
                sendReadReceiptEvent(event.getUserId(), event.getBroadcastId());
                break;
            default:
                log.warn("Unhandled event type from Kafka orchestrator: {}", event.getEventType());
                break;
        }
    }

    private void deliverTargetedMessage(String userId, BroadcastMessage broadcast) {
        // This is the safeguard: we confirm a record exists for this user in user_broadcast_messages
        userBroadcastRepository.findByUserIdAndBroadcastId(userId, broadcast.getId()).ifPresentOrElse(
            userMessage -> {
                log.info("Delivering targeted broadcast {} to user {}", broadcast.getId(), userId);
                UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponseFromEntity(userMessage, broadcast);
                String eventId = response.getUserMessageId() != null
                    ? response.getBroadcastId() + ":" + response.getUserMessageId()
                    : String.valueOf(response.getBroadcastId());
                
                sendSseEvent(userId, SseEventType.MESSAGE, eventId, response);
                messageStatusService.updateMessageToDelivered(userMessage.getId(), broadcast.getId());

                cacheService.evictUserInbox(userId);
            },
            () -> log.warn("Prevented delivery of targeted broadcast {} to user {}. No user_broadcast_messages record found.", broadcast.getId(), userId)
        );
    }

     private void deliverFanOutOnReadMessage(String userId, BroadcastMessage broadcast) {
        log.info("Delivering fan-out-on-read broadcast {} to online user {}", broadcast.getId(), userId);

        // Create a transient message object to ensure the final DTO is complete
        UserBroadcastMessage transientMessage = UserBroadcastMessage.builder()
                .id(broadcast.getId()) // Use broadcastId as a transient ID
                .broadcastId(broadcast.getId())
                .userId(userId)
                .deliveryStatus(Constants.DeliveryStatus.DELIVERED.name())
                .readStatus(Constants.ReadStatus.UNREAD.name())
                .deliveredAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdAt(broadcast.getCreatedAt())
                .build();
        
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponseFromEntity(transientMessage, broadcast);
        String eventId = response.getUserMessageId() != null
            ? response.getBroadcastId() + ":" + response.getUserMessageId()
            : String.valueOf(response.getBroadcastId());

        sendSseEvent(userId, SseEventType.MESSAGE, eventId, response);
        userMessageService.recordDeliveryForFanOutOnRead(userId, broadcast.getId());
    }
    
    private void sendRemoveMessageEvent(String userId, Long broadcastId) {
        Map<String, Long> payload = Map.of("broadcastId", broadcastId);
        sendSseEvent(userId, SseEventType.MESSAGE_REMOVED, broadcastId.toString(), payload);
    }

    private void sendReadReceiptEvent(String userId, Long broadcastId) {
        Map<String, Long> payload = Map.of("broadcastId", broadcastId);
        sendSseEvent(userId, SseEventType.READ_RECEIPT, broadcastId.toString(), payload);
    }

    public void sendSseEvent(String userId, SseEventType eventType, String eventId, Object data) {
        ServerSentEvent<String> sse = sseEventFactory.createEvent(eventType, eventId, data);
        if (sse != null) {
            sseConnectionManager.sendEvent(userId, sse);
        }
    }
    
    public int getConnectedUserCount() {
        return sseConnectionManager.getConnectedUserCount();
    }

    public boolean isUserConnected(String userId) {
        return sseConnectionManager.isUserConnected(userId);
    }

    public void handleBroadcastToAllEvent(MessageDeliveryEvent event) {
        log.debug("Handling generic broadcast event: {}", event.getEventType());

        ServerSentEvent<String> sseEvent = null;

        switch (Constants.EventType.valueOf(event.getEventType())) {
            case CREATED:
                Optional<BroadcastMessage> broadcastOpt = broadcastRepository.findById(event.getBroadcastId());
                if (broadcastOpt.isPresent()) {
                    BroadcastMessage broadcast = broadcastOpt.get();
                    log.info("Delivering generic 'ALL' broadcast {} to all local clients.", broadcast.getId());
                    UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponseFromEntity(null, broadcast);
                    sseEvent = sseEventFactory.createEvent(SseEventType.MESSAGE, response.getBroadcastId().toString(), response);
                }
                break;
            case CANCELLED:
            case EXPIRED:
                Map<String, Long> payload = Map.of("broadcastId", event.getBroadcastId());
                sseEvent = sseEventFactory.createEvent(SseEventType.MESSAGE_REMOVED, event.getBroadcastId().toString(), payload);
                break;
            default:
                log.warn("Unhandled generic event type: {}", event.getEventType());
                break;
        }

        if (sseEvent != null) {
            sseConnectionManager.broadcastEventToLocalConnections(sseEvent);

            // --- THIS IS THE FIX ---
            // Evict the inbox cache for all users connected to this pod
            Set<String> localUserIds = sseConnectionManager.getLocalUserIds();
            log.info("Evicting inbox cache for {} local users due to group-level event.", localUserIds.size());
            for (String userId : localUserIds) {
                cacheService.evictUserInbox(userId);
            }
        }
    }
}