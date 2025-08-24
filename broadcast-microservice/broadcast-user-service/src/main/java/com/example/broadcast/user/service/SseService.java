package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.SseEventType;
import com.example.broadcast.user.service.cache.CacheService;
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

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class SseService {

    private final BroadcastRepository broadcastRepository;
    private final BroadcastMapper broadcastMapper;
    private final SseConnectionManager sseConnectionManager;
    private final CacheService cacheService;
    private final UserMessageService userMessageService;
    private final ObjectMapper objectMapper;
    private final Scheduler jdbcScheduler;
    private final AppProperties appProperties;

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
                // First, process pending messages and get the IDs of what was sent
                Set<Long> processedIds = sendPendingMessages(userId);
                // Then, process general active messages, excluding what was just sent
                sendActiveGroupMessages(userId, processedIds);
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
        switch (Constants.EventType.valueOf(event.getEventType())) {
            case CREATED:
                broadcastRepository.findById(event.getBroadcastId()).ifPresentOrElse(
                    broadcast -> deliverFanOutOnReadMessage(event.getUserId(), broadcast),
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

    private void deliverFanOutOnReadMessage(String userId, BroadcastMessage broadcast) {
        log.info("Delivering fan-out-on-read broadcast {} to online user {}", broadcast.getId(), userId);
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
        sendSseEvent(userId, SseEventType.MESSAGE, response.getId().toString(), response);
        userMessageService.processAndCountGroupMessageDelivery(userId, broadcast);
    }
    
    private Set<Long> sendPendingMessages(String userId) {
        String clusterName = appProperties.getClusterName();
        List<MessageDeliveryEvent> pendingEvents = cacheService.getPendingEvents(userId,clusterName);
        if (pendingEvents == null || pendingEvents.isEmpty()) {
            return Collections.emptySet();
        }

        log.info("Found {} pending events in cache for user: {}", pendingEvents.size(), userId);
        Set<Long> processedIds = new HashSet<>();
        for (MessageDeliveryEvent event : pendingEvents) {
            processedIds.add(event.getBroadcastId());
            switch (Constants.EventType.valueOf(event.getEventType())) {
                case CREATED:
                    broadcastRepository.findById(event.getBroadcastId()).ifPresent(broadcast -> {
                        log.info("Processing pending CREATED event for user {} and broadcast {}", userId, event.getBroadcastId());
                        deliverFanOutOnReadMessage(event.getUserId(), broadcast);
                    });
                    break;
                case CANCELLED:
                case EXPIRED:
                    log.info("Processing pending CANCELLED/EXPIRED event for user {} and broadcast {}", userId, event.getBroadcastId());
                    sendRemoveMessageEvent(userId, event.getBroadcastId());
                    break;
                default:
                    log.warn("Unhandled pending event type: {}", event.getEventType());
                    break;
            }
        }
        cacheService.clearPendingEvents(userId, clusterName);
        return processedIds;
    }

    private void sendActiveGroupMessages(String userId, Set<Long> excludedIds) {
        log.info("Checking for active group (ALL/ROLE) messages for newly connected user: {}, excluding {} already processed.", userId, excludedIds.size());
        userMessageService.getActiveBroadcastsForUser(userId)
            .subscribe(groupMessages -> {
                if (!groupMessages.isEmpty()) {
                    List<BroadcastMessage> messagesToDeliver = groupMessages.stream()
                        .filter(broadcast -> !excludedIds.contains(broadcast.getId()))
                        .collect(Collectors.toList());
                    
                    if (!messagesToDeliver.isEmpty()) {
                        log.info("Delivering {} new active group messages to user: {}", messagesToDeliver.size(), userId);
                        for (BroadcastMessage broadcast : messagesToDeliver) {
                            UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
                            sendSseEvent(userId, SseEventType.MESSAGE, response.getId().toString(), response);
                            userMessageService.processAndCountGroupMessageDelivery(userId, broadcast);
                        }
                    }
                }
            }, error -> log.error("Failed to fetch active group messages for user {}", userId, error));
    }

    private void sendRemoveMessageEvent(String userId, Long broadcastId) {
        Map<String, Long> payload = Map.of("broadcastId", broadcastId);
        sendSseEvent(userId, SseEventType.MESSAGE_REMOVED, broadcastId.toString(), payload);
    }

    private void sendReadReceiptEvent(String userId, Long broadcastId) {
        Map<String, Long> payload = Map.of("broadcastId", broadcastId);
        sendSseEvent(userId, SseEventType.READ_RECEIPT, broadcastId.toString(), payload);
    }

    private void sendSseEvent(String userId, SseEventType eventType, String eventId, Object data) {
        try {
            String payload = objectMapper.writeValueAsString(data);
            ServerSentEvent<String> sse = ServerSentEvent.<String>builder()
                .event(eventType.name())
                .data(payload)
                .id(eventId)
                .build();
            sseConnectionManager.sendEvent(userId, sse);
        } catch (JsonProcessingException e) {
            log.error("Error serializing payload for SSE event type {}: {}", eventType, e.getMessage());
        }
    }
    
    public int getConnectedUserCount() {
        return sseConnectionManager.getConnectedUserCount();
    }

    public boolean isUserConnected(String userId) {
        return sseConnectionManager.isUserConnected(userId);
    }
}