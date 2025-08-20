package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
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

        // 1. Get the live event stream from the manager immediately.
        Flux<ServerSentEvent<String>> liveStream = sseConnectionManager.createEventStream(userId, connectionId);

        // 2. Get a separate, cold stream that will do the heavy work of fetching initial messages.
        Flux<Void> initialMessagesStream = getInitialMessagesFlux(userId);

        // 3. Merge the two streams. The connection is established instantly by liveStream.
        return Flux.merge(liveStream, initialMessagesStream.thenMany(Flux.empty()));
    }

    // REPLACE the existing getInitialMessagesFlux method with this corrected version
    private Flux<Void> getInitialMessagesFlux(String userId) {
        // CORRECTED: Use Mono.fromRunnable for broader compatibility. It is the correct operator for void, blocking tasks.
        return Mono.fromRunnable(() -> {
            try {
                log.info("Starting async fetch of initial messages for user: {}", userId);
                // These are the blocking calls that were causing the timeout
                sendPendingMessages(userId);
                sendActiveGroupMessages(userId);
                log.info("Finished async fetch of initial messages for user: {}", userId);
            } catch (Exception e) {
                log.error("Error during async initial message delivery for user: {}", userId, e);
            }
        })
        // Run this blocking JDBC work on a dedicated scheduler
        .subscribeOn(this.jdbcScheduler)
        .then() // Ignores the result of the Mono and returns an empty Mono<Void> on completion
        .flux(); // Converts the empty Mono<Void> to an empty Flux<ServerSentEvent<String>>
    }

    @Transactional
    public void removeEventStream(String userId, String connectionId) {
        sseConnectionManager.removeEventStream(userId, connectionId);
    }

    /**
     * Main entry point for processing a CREATED event from Kafka.
     * This logic is now unified for ALL, ROLE, and SELECTED types.
     */
    public void handleMessageEvent(MessageDeliveryEvent event) {
        log.debug("Orchestrating message event: {} for user: {}", event.getEventType(), event.getUserId());
        
        if (!event.getEventType().equals(Constants.EventType.CREATED.name())) {
            handleLifecycleEvent(event); // Handles READ, CANCELLED, EXPIRED
            return;
        }

        // For ALL CREATED events (ALL, ROLE, SELECTED), fetch the broadcast content.
        Optional<BroadcastMessage> broadcastOpt = broadcastRepository.findById(event.getBroadcastId());
        if (broadcastOpt.isEmpty()) {
            log.error("Cannot process event. BroadcastMessage with ID {} not found.", event.getBroadcastId());
            return;
        }

        // UNIFIED LOGIC: All fan-out-on-read types are delivered the same way.
        deliverFanOutOnReadMessage(event.getUserId(), broadcastOpt.get());
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
     * RENAMED & UNIFIED: Delivers a broadcast to a user for any fan-out-on-read type.
     * It creates the response on-the-fly without checking for a pre-existing DB record.
     */
    private void deliverFanOutOnReadMessage(String userId, BroadcastMessage broadcast) {
        log.info("Delivering fan-out-on-read broadcast {} to online user {}", broadcast.getId(), userId);
        
        UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
        sendSseEvent(userId, response);

        // DELEGATE the counting to the new idempotent service method.
        userMessageService.processAndCountGroupMessageDelivery(userId, broadcast);
    }


    /**
     * This method is now only used for delivering PENDING messages to users who were offline
     * and have just reconnected. It is NO LONGER used for new real-time CREATED events.
     */
    @Transactional
    public void deliverSelectedUserMessage(String userId, BroadcastMessage broadcast) {
        UserBroadcastMessage message = userBroadcastRepository
                .findByUserIdAndBroadcastId(userId, broadcast.getId())
                .filter(msg -> msg.getDeliveryStatus().equals(Constants.DeliveryStatus.PENDING.name()))
                .orElse(null);

        if (message == null) {
            log.warn("Skipping pending delivery. No PENDING UserBroadcastMessage found for user {} and broadcast {}", userId, broadcast.getId());
            return;
        }

        buildUserBroadcastResponse(message)
            .ifPresent(response -> {
                sendSseEvent(userId, response);
                if (isUserConnected(userId)) {
                    messageStatusService.updateMessageToDelivered(message.getId(), broadcast.getId());
                    log.info("Delivered PENDING message and updated status for user: {}, broadcast: {}", userId, broadcast.getId());
                }
            });
    }

    private void sendPendingMessages(String userId) {
        // 1. First, check the Redis cache for any recently missed messages.
        List<MessageDeliveryEvent> pendingEvents = cacheService.getPendingEvents(userId);
        
        if (pendingEvents != null && !pendingEvents.isEmpty()) { 
            log.info("Found {} pending messages in cache for user: {}", pendingEvents.size(), userId);
            for (MessageDeliveryEvent event : pendingEvents) { 
                broadcastRepository.findById(event.getBroadcastId()).ifPresent(broadcast -> {
                    deliverFanOutOnReadMessage(event.getUserId(), broadcast);
                });
            }
            // After sending, clear the pending events cache for this user.
            cacheService.clearPendingEvents(userId);
            return;
        }
    }

    private void sendActiveGroupMessages(String userId) {
        log.info("Checking for active group (ALL/ROLE) messages for newly connected user: {}", userId);
        // This method now returns BroadcastMessage objects directly
        List<BroadcastMessage> groupMessages = userMessageService.getActiveBroadcastsForUser(userId);

        if (!groupMessages.isEmpty()) {
            log.info("Delivering {} active group messages to user: {}", groupMessages.size(), userId);
            for (BroadcastMessage broadcast : groupMessages) {
                // Create the response DTO for the SSE event
                UserBroadcastResponse response = broadcastMapper.toUserBroadcastResponse(null, broadcast);
                sendSseEvent(userId, response);
                
                // DELEGATE the counting to the new idempotent service method.
                userMessageService.processAndCountGroupMessageDelivery(userId, broadcast);
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