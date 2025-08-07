package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.repository.UserSessionRepository;
import com.example.broadcast.shared.util.Constants.DeliveryStatus;
import com.example.broadcast.shared.util.Constants.EventType;
import com.example.broadcast.shared.util.Constants.SseEventType;
import com.example.broadcast.shared.service.cache.CacheService;
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
    private final UserSessionRepository userSessionRepository;
    private final ObjectMapper objectMapper;
    private final BroadcastRepository broadcastRepository;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService;
    private final AppProperties appProperties;
    private final BroadcastMapper broadcastMapper;
    
    // DEPENDENCY INJECTION for the connection manager
    private final SseConnectionManager sseConnectionManager;

    // REMOVED: All in-memory maps (userSinks, etc.) are gone from this class.
    // REMOVED: The duplicated cleanupStaleSessions, init, cleanup, and startServerHeartbeat methods are gone.

    @Transactional
    public void registerConnection(String userId, String sessionId) {
        // DELEGATE responsibility to the connection manager
        sseConnectionManager.registerConnection(userId, sessionId);
    }
    
    public Flux<ServerSentEvent<String>> createEventStream(String userId, String sessionId) {
        log.info("Orchestrating event stream creation for user: {}, session: {}", userId, sessionId);
        
        // DELEGATE stream creation to the connection manager
        Flux<ServerSentEvent<String>> eventStream = sseConnectionManager.createEventStream(userId, sessionId);

        // This service's responsibility is now higher-level logic, like sending pending messages on connect.
        sendPendingMessages(userId);
        
        try {
            String connectedPayload = objectMapper.writeValueAsString(Map.of("message", "SSE connection established with session " + sessionId));
            // DELEGATE sending the event
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
        // DELEGATE responsibility to the connection manager
        sseConnectionManager.removeEventStream(userId, sessionId);
    }

    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Orchestrating message event: {} for user: {}", event.getEventType(), event.getUserId());
        try {
            String payload = objectMapper.writeValueAsString(Map.of("broadcastId", event.getBroadcastId()));
            switch (EventType.valueOf(event.getEventType())) {
                case CREATED:
                    deliverMessageToUser(event.getUserId(), event.getBroadcastId());
                    break;
                case READ:
                case EXPIRED:
                case CANCELLED:
                    // DELEGATE sending the event
                    sseConnectionManager.sendEvent(event.getUserId(), ServerSentEvent.<String>builder().event(SseEventType.MESSAGE_REMOVED.name()).data(payload).build());
                    break;
            }
        } catch (JsonProcessingException e) {
            log.error("Error processing message event for SSE", e);
        }
    }

    private void sendPendingMessages(String userId) {
        List<UserBroadcastMessage> pendingMessages = userBroadcastRepository.findPendingMessages(userId);
        for (UserBroadcastMessage message : pendingMessages) {
            deliverMessageToUser(userId, message.getBroadcastId());
        }
        if (!pendingMessages.isEmpty()) {
            log.info("Sent {} pending messages to user: {}", pendingMessages.size(), userId);
        }
    }

    @Transactional
    public void deliverMessageToUser(String userId, Long broadcastId) {
        UserBroadcastMessage message = userBroadcastRepository
                .findByUserIdAndBroadcastId(userId, broadcastId)
                .filter(msg -> msg.getDeliveryStatus().equals(DeliveryStatus.PENDING.name()))
                .orElse(null);
        if (message == null) {
            log.warn("Skipping delivery. No PENDING UserBroadcastMessage found for user {} and broadcast {}", userId, broadcastId);
            return;
        }

        buildUserBroadcastResponse(message)
            .ifPresent(response -> {
                try {
                    String payload = objectMapper.writeValueAsString(response);
                    ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                        .event(SseEventType.MESSAGE.name())
                        .data(payload)
                        .id(String.valueOf(response.getId()))
                        .build();
                  
                    // DELEGATE sending the event
                    sseConnectionManager.sendEvent(userId, sse);
                    
                    if (isUserConnected(userId)) {
                        messageStatusService.updateMessageToDelivered(message.getId(), broadcastId);
                        log.info("Message delivered to online user: {}, broadcast: {}", userId, broadcastId);
                    } else {
                        log.warn("Delivery attempt for user {} and broadcast {} aborted, user disconnected during process.", userId, broadcastId);
                    }

                } catch (JsonProcessingException e) {
                    log.error("Error delivering message to user as SSE", e);
                }
            });
    }

    private Optional<UserBroadcastResponse> buildUserBroadcastResponse(UserBroadcastMessage message) {
        return broadcastRepository.findById(message.getBroadcastId())
            .map(broadcast -> broadcastMapper.toUserBroadcastResponse(message, broadcast));
    }

    public int getConnectedUserCount() {
        // DELEGATE responsibility to the connection manager
        return sseConnectionManager.getConnectedUserCount();
    }

    public boolean isUserConnected(String userId) {
        // DELEGATE responsibility to the connection manager
        return sseConnectionManager.isUserConnected(userId);
    }
}