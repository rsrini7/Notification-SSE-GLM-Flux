package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.shared.dto.cache.UserMessageInfo;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.DeliveryStatus;
import com.example.broadcast.shared.util.Constants.EventType;
import com.example.broadcast.shared.util.Constants.SseEventType;
import com.example.broadcast.shared.service.MessageStatusService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SseService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final ObjectMapper objectMapper;
    private final BroadcastRepository broadcastRepository;
    private final MessageStatusService messageStatusService;
    private final BroadcastMapper broadcastMapper;
    private final SseConnectionManager sseConnectionManager;
    private final CacheService cacheService;

    @Transactional
    public void registerConnection(String userId, String sessionId) {
        sseConnectionManager.registerConnection(userId, sessionId);
    }
    
    public Flux<ServerSentEvent<String>> createEventStream(String userId, String sessionId) {
        log.info("Orchestrating event stream creation for user: {}, session: {}", userId, sessionId);
        Flux<ServerSentEvent<String>> eventStream = sseConnectionManager.createEventStream(userId, sessionId);

        sendPendingMessages(userId);
        try {
            String connectedPayload = objectMapper.writeValueAsString(Map.of("message", "SSE connection established with session " + sessionId));
            sseConnectionManager.sendEvent(userId, ServerSentEvent.<String>builder()
                .event(SseEventType.CONNECTED.name())
                .data(connectedPayload)
                .build());
        } catch (JsonProcessingException e) {
            log.error("Error creating CONNECTED event", e);
        }
        
        return eventStream;
    }

    @Transactional
    public void removeEventStream(String userId, String sessionId) {
        sseConnectionManager.removeEventStream(userId, sessionId);
    }

    /**
     * This is the main entry point for processing an incoming message event.
     * It now differentiates between delivery strategies.
     */
    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Orchestrating message event: {} for user: {}", event.getEventType(), event.getUserId());

        // For non-creation events, we can use a simpler path.
        if (event.getEventType() != EventType.CREATED.name()) {
            handleLifecycleEvent(event);
            return;
        }

        // For CREATED events, we need the full BroadcastMessage to determine the delivery strategy.
        Optional<BroadcastMessage> broadcastOpt = broadcastRepository.findById(event.getBroadcastId());
        if (broadcastOpt.isEmpty()) {
            log.error("Cannot process event. BroadcastMessage with ID {} not found.", event.getBroadcastId());
            return;
        }
        BroadcastMessage broadcast = broadcastOpt.get();
        String targetType = broadcast.getTargetType();

        // **CORRECTED LOGIC: Route to the correct delivery method based on target type.**
        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            // Use the original, database-dependent delivery method for targeted messages.
            deliverSelectedUserMessage(event.getUserId(), broadcast);
        } else {
            // Use the new, direct-to-SSE delivery method for group messages.
            deliverGroupUserMessage(event.getUserId(), broadcast);
        }
    }

    /**
     * Handles lifecycle events like READ, EXPIRED, CANCELLED that simply remove a message from the UI.
     */
    private void handleLifecycleEvent(MessageDeliveryEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of("broadcastId", event.getBroadcastId()));
            sseConnectionManager.sendEvent(event.getUserId(), ServerSentEvent.<String>builder()
                .event(SseEventType.MESSAGE_REMOVED.name())
                .data(payload).build());
        } catch (JsonProcessingException e) {
            log.error("Error processing lifecycle event for SSE", e);
        }
    }
    
    /**
     * New method for delivering ROLE and ALL broadcasts.
     * It bypasses the database check and sends the message directly.
     */
    private void deliverGroupUserMessage(String userId, BroadcastMessage broadcast) {
        log.info("Delivering group (ROLE/ALL) broadcast {} to online user {}", broadcast.getId(), userId);
        // We can't use the original 'buildUserBroadcastResponse' as it requires a UserBroadcastMessage.
        // We create the response directly from the parent BroadcastMessage.
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
        sendSseEvent(userId, response);
    }

    /**
     * The original delivery logic, now specifically for SELECTED users.
     * This still requires a PENDING database record.
     */
    @Transactional
    public void deliverSelectedUserMessage(String userId, BroadcastMessage broadcast) {
        UserBroadcastMessage message = userBroadcastRepository
                .findByUserIdAndBroadcastId(userId, broadcast.getId())
                .filter(msg -> msg.getDeliveryStatus().equals(DeliveryStatus.PENDING.name()))
                .orElse(null);
        if (message == null) {
            log.warn("Skipping delivery. No PENDING UserBroadcastMessage found for user {} and broadcast {}", userId, broadcast.getId());
            return;
        }

        buildUserBroadcastResponse(message)
            .ifPresent(response -> {
                sendSseEvent(userId, response);
                if (isUserConnected(userId)) {
                    messageStatusService.updateMessageToDelivered(message.getId(), broadcast.getId());
                    cacheService.addMessageToUserCache(userId, new UserMessageInfo(
                        message.getId(), message.getBroadcastId(), DeliveryStatus.DELIVERED.name(), 
                        message.getReadStatus(), message.getCreatedAt()
                    ));
                    log.info("Message delivered and status updated for SELECTED user: {}, broadcast: {}", userId, broadcast.getId());
                }
            });
    }

    private void sendPendingMessages(String userId) {
        List<MessageDeliveryEvent> pendingEvents = cacheService.getPendingEvents(userId);

        if (!pendingEvents.isEmpty()) {
            log.info("Found {} pending messages in cache for user: {}", pendingEvents.size(), userId);
            for (MessageDeliveryEvent event : pendingEvents) {
                // We must use the original, database-driven delivery method for pending messages.
                broadcastRepository.findById(event.getBroadcastId()).ifPresent(broadcast -> {
                    deliverSelectedUserMessage(event.getUserId(), broadcast);
                });
            }
            cacheService.clearPendingEvents(userId);
            return;
        }

        List<UserBroadcastMessage> pendingMessages = userBroadcastRepository.findPendingMessages(userId);
        if (!pendingMessages.isEmpty()) {
            log.warn("Cache was empty, but found {} pending messages in DB for user: {}", pendingMessages.size(), userId);
            for (UserBroadcastMessage message : pendingMessages) {
                broadcastRepository.findById(message.getBroadcastId()).ifPresent(broadcast -> {
                    deliverSelectedUserMessage(userId, broadcast);
                });
            }
        }
    }
    
    /**
     * Helper method to centralize the sending of the SSE event itself.
     */
    private void sendSseEvent(String userId, UserBroadcastResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .event(SseEventType.MESSAGE.name())
                .data(payload)
                .id(String.valueOf(response.getId()))
                .build();
            
            sseConnectionManager.sendEvent(userId, sse);

            if ("Force Logoff".equalsIgnoreCase(response.getCategory())) {
                log.warn("Force Logoff message delivered to user {}. Terminating their session.", userId);
                sseConnectionManager.removeEventStream(userId, sseConnectionManager.getSessionIdForUser(userId));
            }
        } catch (JsonProcessingException e) {
            log.error("Error delivering message to user as SSE", e);
        }
    }

    private Optional<UserBroadcastResponse> buildUserBroadcastResponse(UserBroadcastMessage message) {
        Optional<BroadcastMessage> broadcastOpt = cacheService.getBroadcastContent(message.getBroadcastId());

        if (broadcastOpt.isEmpty()) {
            broadcastOpt = broadcastRepository.findById(message.getBroadcastId());
            broadcastOpt.ifPresent(cacheService::cacheBroadcastContent);
        }

        return broadcastOpt.map(broadcast -> broadcastMapper.toUserBroadcastResponse(message, broadcast));
    }

    public int getConnectedUserCount() {
        return sseConnectionManager.getConnectedUserCount();
    }

    public boolean isUserConnected(String userId) {
        return sseConnectionManager.isUserConnected(userId);
    }
}