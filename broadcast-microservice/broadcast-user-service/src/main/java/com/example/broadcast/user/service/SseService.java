package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.SseEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;

@Service
@Slf4j
@RequiredArgsConstructor
public class SseService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository; 
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final BroadcastMapper broadcastMapper;
    private final SseConnectionManager sseConnectionManager;
    private final SseEventFactory sseEventFactory; 
    private final UserMessageService userMessageService;
    private final MessageStatusService messageStatusService;

    @Transactional
    public void registerConnection(String userId, String connectionId) {
        sseConnectionManager.registerConnection(userId, connectionId);
    }

    /**
     * Establishes the live SSE stream immediately and triggers
     * the fetch for historical messages as a separate, non-blocking operation.
     */
    public Flux<ServerSentEvent<String>> createEventStream(String userId, String connectionId) {
        log.info("Establishing event stream for user: {}, connection: {}", userId, connectionId);

        // 1. Create the live, infinite stream that will be returned to the client.
        Flux<ServerSentEvent<String>> liveStream = sseConnectionManager.createEventStream(userId, connectionId);

        // 2. Trigger the fetch for historical/pending messages as a separate, fire-and-forget operation.
        // We subscribe here to kick it off, but we do NOT merge it into the liveStream.
        // This prevents the database stream from terminating the live SSE connection.
        userMessageService.getUserMessages(userId)
            .flatMapMany(Flux::fromIterable)
            .subscribe(
                response -> {
                    // This logic runs for each historical message found in the DB
                    log.info("Delivering persisted message for broadcast {} to newly connected user {}", response.getBroadcastId(), userId);
                    sendSseEvent(userId, SseEventType.MESSAGE, response.getBroadcastId().toString(), response);
                },
                error -> log.error("Error fetching initial messages from DB for user {}", userId, error)
            );

        // 3. Return the live stream to the user immediately.
        return liveStream;
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
                sendSseEvent(userId, SseEventType.MESSAGE, response.getId().toString(), response);
                messageStatusService.updateMessageToDelivered(userMessage.getId(), broadcast.getId());
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
                .deliveredAt(ZonedDateTime.now(ZoneOffset.UTC))
                .createdAt(broadcast.getCreatedAt())
                .build();
        
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponseFromEntity(transientMessage, broadcast);
        sendSseEvent(userId, SseEventType.MESSAGE, response.getId().toString(), response);
        broadcastStatisticsRepository.incrementDeliveredCount(broadcast.getId());
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
}