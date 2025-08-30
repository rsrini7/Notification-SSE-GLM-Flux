package com.example.broadcast.user.service;

import com.example.broadcast.shared.util.Constants.SseEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SseEventFactory {

    private final ObjectMapper objectMapper;

    /**
     * Generic method to create any SSE event.
     * @param eventType The type of the event (e.g., MESSAGE, HEARTBEAT).
     * @param eventId A unique ID for the event.
     * @param data The payload object to be serialized to JSON.
     * @return A fully constructed ServerSentEvent, or null if serialization fails.
     */
    public ServerSentEvent<String> createEvent(SseEventType eventType, String eventId, Object data) {
        try {
            String payload = objectMapper.writeValueAsString(data);
            return ServerSentEvent.<String>builder()
                .event(eventType.name())
                .id(eventId)
                .data(payload)
                .build();
        } catch (JsonProcessingException e) {
            log.error("Error serializing payload for SSE event type {}: {}", eventType, e.getMessage());
            return null;
        }
    }
    
    public ServerSentEvent<String> createHeartbeatEvent() {
        Map<String, String> data = Map.of("timestamp", OffsetDateTime.now().toString());
        return createEvent(SseEventType.HEARTBEAT, null, data);
    }

    public ServerSentEvent<String> createConnectedEvent(String connectionId) {
         Map<String, String> data = Map.of(
            "message", "SSE connection established",
            "connectionId", connectionId,
            "timestamp", OffsetDateTime.now().toString()
        );
        return createEvent(SseEventType.CONNECTED, connectionId, data);
    }
    
    public ServerSentEvent<String> createShutdownEvent() {
        return ServerSentEvent.<String>builder()
               .event(SseEventType.SERVER_SHUTDOWN.name())
               .data("Server is shutting down. Please reconnect momentarily.")
               .build();
    }
}