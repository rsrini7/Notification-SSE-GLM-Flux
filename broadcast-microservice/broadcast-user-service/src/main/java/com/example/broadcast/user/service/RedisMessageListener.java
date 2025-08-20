package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.service.TestingConfigurationService;
import com.example.broadcast.shared.util.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisMessageListener implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SseService sseService;
    private final CacheService cacheService;
    private final TestingConfigurationService testingConfigurationService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            MessageDeliveryEvent event = objectMapper.readValue(message.getBody(), MessageDeliveryEvent.class);
            
            if (testingConfigurationService.isMarkedForFailure(event.getBroadcastId())) {
                log.warn("DLT TEST MODE: Simulating failure for broadcast ID via Redis Pub/Sub: {}", event.getBroadcastId());
                throw new RuntimeException("Simulating DLT failure for broadcast ID: " + event.getBroadcastId());
            }

            log.info("[WORKER_REDIS_CONSUME] Processing event for broadcastId='{}', userId='{}', eventType='{}'",
                 event.getBroadcastId(), event.getUserId(), event.getEventType());
            log.debug("Worker received event from Redis channel '{}'. Payload: {}", new String(pattern), event);
            
            switch (Constants.EventType.valueOf(event.getEventType())) {
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
                    log.warn("Unhandled event type on worker channel: {}", event.getEventType());
            }
        } catch (IOException e) {
            log.error("Failed to deserialize message from Redis Pub/Sub. Raw message: {}", new String(message.getBody()), e);
        } catch (Exception e) {
            log.error("Failed to process worker event from Redis Pub/Sub. Root cause: {}", e.getMessage(), e);
            // In a production system, you might add more robust error handling here,
            // but since Redis Pub/Sub is fire-and-forget, there's no acknowledgment or DLT.
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