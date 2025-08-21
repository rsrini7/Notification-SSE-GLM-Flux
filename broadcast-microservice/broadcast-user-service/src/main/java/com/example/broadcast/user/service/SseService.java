package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.SseEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class SseService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final MessageStatusService messageStatusService;
    private final BroadcastMapper broadcastMapper;
    private final SseConnectionManager sseConnectionManager;
    private final CacheService cacheService;
    private final UserMessageService userMessageService;
    private final ObjectMapper objectMapper;
    private final Scheduler jdbcScheduler;

    @Transactional
    public void registerConnection(String userId, String connectionId) {
        sseConnectionManager.registerConnection(userId, connectionId);
    }

    public Flux<ServerSentEvent<String>> createEventStream(String userId, String connectionId) {
        log.info("Establishing event stream for user: {}, connection: {}", userId, connectionId);
        Flux<ServerSentEvent<String>> liveStream = sseConnectionManager.createEventStream(userId, connectionId);
        Flux<Void> initialMessagesStream = getInitialMessagesFlux(userId);
        return Flux.merge(liveStream, initialMessagesStream.thenMany(Flux.empty()));
    }

    private Flux<Void> getInitialMessagesFlux(String userId) {
        return Mono.fromRunnable(() -> {
            try {
                log.info("Starting async fetch of initial messages for user: {}", userId);
                sendPendingMessages(userId);
                // MODIFIED: This now subscribes to the Mono to execute the logic
                sendActiveGroupMessages(userId);
            } catch (Exception e) {
                log.error("Error during async initial message delivery for user: {}", userId, e);
            }
        })
        .subscribeOn(this.jdbcScheduler)
        .then()
        .flux();
    }

    @Transactional
    public void removeEventStream(String userId, String connectionId) {
        sseConnectionManager.removeEventStream(userId, connectionId);
    }

    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Orchestrating message event: {} for user: {}", event.getEventType(), event.getUserId());
        if (!event.getEventType().equals(Constants.EventType.CREATED.name())) {
            handleLifecycleEvent(event);
            return;
        }
        Optional<BroadcastMessage> broadcastOpt = broadcastRepository.findById(event.getBroadcastId());
        if (broadcastOpt.isEmpty()) {
            log.error("Cannot process event. BroadcastMessage with ID {} not found.", event.getBroadcastId());
            return;
        }
        deliverFanOutOnReadMessage(event.getUserId(), broadcastOpt.get());
    }

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

    private void deliverFanOutOnReadMessage(String userId, BroadcastMessage broadcast) {
        log.info("Delivering fan-out-on-read broadcast {} to online user {}", broadcast.getId(), userId);
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
        sendSseEvent(userId, response);
        userMessageService.processAndCountGroupMessageDelivery(userId, broadcast);
    }

    private void sendPendingMessages(String userId) {
        List<MessageDeliveryEvent> pendingEvents = cacheService.getPendingEvents(userId);
        if (pendingEvents != null && !pendingEvents.isEmpty()) {
            log.info("Found {} pending messages in cache for user: {}", pendingEvents.size(), userId);
            for (MessageDeliveryEvent event : pendingEvents) {
                broadcastRepository.findById(event.getBroadcastId()).ifPresent(broadcast -> {
                    deliverFanOutOnReadMessage(event.getUserId(), broadcast);
                });
            }
            cacheService.clearPendingEvents(userId);
        }
    }

    // MODIFIED: This method now handles the reactive type from the service
    private void sendActiveGroupMessages(String userId) {
        log.info("Checking for active group (ALL/ROLE) messages for newly connected user: {}", userId);
        userMessageService.getActiveBroadcastsForUser(userId)
            .subscribe(groupMessages -> {
                if (!groupMessages.isEmpty()) {
                    log.info("Delivering {} active group messages to user: {}", groupMessages.size(), userId);
                    for (BroadcastMessage broadcast : groupMessages) {
                        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
                        sendSseEvent(userId, response);
                        userMessageService.processAndCountGroupMessageDelivery(userId, broadcast);
                    }
                }
            }, error -> log.error("Failed to fetch active group messages for user {}", userId, error));
    }

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

    public int getConnectedUserCount() {
        return sseConnectionManager.getConnectedUserCount();
    }

    public boolean isUserConnected(String userId) {
        return sseConnectionManager.isUserConnected(userId);
    }
}