package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
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
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;    
    private final MessageStatusService messageStatusService;
    private final BroadcastMapper broadcastMapper;
    private final SseConnectionManager sseConnectionManager;
    private final CacheService cacheService;
    private final UserMessageService userMessageService;

    @Transactional
    public void registerConnection(String userId, String connectionId) {
        sseConnectionManager.registerConnection(userId, connectionId);
    }

    public Flux<ServerSentEvent<String>> createEventStream(String userId, String connectionId) {
        log.info("Orchestrating event stream creation for user: {}, connection: {}", userId, connectionId);
        Flux<ServerSentEvent<String>> eventStream = sseConnectionManager.createEventStream(userId, connectionId);

        sendPendingMessages(userId);
        sendActiveGroupMessages(userId);

        try {
            String connectedPayload = objectMapper.writeValueAsString(Map.of("message", "SSE connection established with connection " + connectionId));
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
    public void removeEventStream(String userId, String connectionId) {
        sseConnectionManager.removeEventStream(userId, connectionId);
    }

    /**
     * This is the main entry point for processing an incoming message event.
     * It now differentiates between delivery strategies.
     */
    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Orchestrating message event: {} for user: {}", event.getEventType(), event.getUserId());
        
        // For non-creation events, we can use a simpler path.
        if (!event.getEventType().equals(EventType.CREATED.name())) {
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
        
        // Route to the correct delivery method based on target type.
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
     * For delivering ROLE and ALL broadcasts.
     * It bypasses the database check and sends the message directly.
     */
    private void deliverGroupUserMessage(String userId, BroadcastMessage broadcast) {
        log.info("Delivering group (ROLE/ALL) broadcast {} to online user {}", broadcast.getId(), userId);
        // We create the response directly from the parent BroadcastMessage, passing null for the UserBroadcastMessage.
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
        sendSseEvent(userId, response);

        broadcastStatisticsRepository.incrementDeliveredCount(broadcast.getId());
        log.debug("Incremented delivered count for group broadcast ID: {}", broadcast.getId());
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
                    log.info("Message delivered and status updated for SELECTED user: {}, broadcast: {}", userId, broadcast.getId());
                }
            });
    }

    private void sendPendingMessages(String userId) {
        // --- START: THIS IS THE CORRECTED LOGIC ---

        // 1. First, check the cache for any recently missed messages.
        List<MessageDeliveryEvent> pendingEvents = cacheService.getPendingEvents(userId);
        
        // 2. If events are found in the cache, deliver them and STOP.
        if (pendingEvents != null && !pendingEvents.isEmpty()) {
            log.info("Found {} pending messages in cache for user: {}", pendingEvents.size(), userId);
            for (MessageDeliveryEvent event : pendingEvents) {
                // Re-fetch the broadcast to ensure it's still active before delivering
                broadcastRepository.findById(event.getBroadcastId()).ifPresent(broadcast -> {
                    
                    // Route to the correct delivery method based on the original broadcast's target type
                    if (Constants.TargetType.SELECTED.name().equals(broadcast.getTargetType())) {
                        deliverSelectedUserMessage(event.getUserId(), broadcast);
                    } else {
                        deliverGroupUserMessage(event.getUserId(), broadcast);
                    }

                });
            }
            // After sending, clear the pending events cache for this user.
            cacheService.clearPendingEvents(userId);
            return; // This 'return' is critical.
        }

        // 3. Only if the cache is empty, check the database for older, targeted messages.
        List<UserBroadcastMessage> pendingDbMessages = userBroadcastRepository.findPendingMessages(userId);
        if (!pendingDbMessages.isEmpty()) {
            log.warn("Cache was empty, but found {} pending messages in DB for user: {}", pendingDbMessages.size(), userId);
            for (UserBroadcastMessage message : pendingDbMessages) {
                broadcastRepository.findById(message.getBroadcastId()).ifPresent(broadcast -> {
                    deliverSelectedUserMessage(userId, broadcast);
                });
            }
        }
    }

    private void sendActiveGroupMessages(String userId) {
        log.info("Checking for active group (ALL/ROLE) messages for newly connected user: {}", userId);
        // Reuse the logic from UserMessageService to get all relevant group messages for this user.
        List<UserBroadcastResponse> groupMessages = userMessageService.getGroupMessagesForUser(userId);

        if (!groupMessages.isEmpty()) {
            log.info("Delivering {} active group messages to user: {}", groupMessages.size(), userId);
            for (UserBroadcastResponse response : groupMessages) {
                sendSseEvent(userId, response);

                broadcastStatisticsRepository.incrementDeliveredCount(response.getBroadcastId());
            }
        }
    }
    
    /**
     * To centralize the sending of the SSE event itself.
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

    public void deliverGroupBroadcastFromEvent(MessageDeliveryEvent event) {
        // Build a temporary BroadcastMessage stub from the event data
        BroadcastMessage broadcastStub = BroadcastMessage.builder()
                .id(event.getBroadcastId())
                .content(event.getMessage())
                .senderName("System")
                .priority("NORMAL")
                .category("GENERAL")
                .createdAt(event.getTimestamp())
                .build();

        // Use the existing mapper to create the final response DTO
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcastStub);
        sendSseEvent(event.getUserId(), response);
    }
}