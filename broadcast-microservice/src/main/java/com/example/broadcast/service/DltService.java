package com.example.broadcast.service;

import com.example.broadcast.dto.DltMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class DltService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, DltMessage> dltMessages = new ConcurrentHashMap<>();

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name:broadcast-events}.DLT",
            groupId = "broadcast-dlt-group", // A separate group for the DLT
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void listenToDlt(
            @Payload byte[] payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.DLT_ORIGINAL_TOPIC) String originalTopic,
            @Header(KafkaHeaders.DLT_ORIGINAL_PARTITION) int originalPartition,
            @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET) long originalOffset,
            @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage,
            @Header(KafkaHeaders.DLT_EXCEPTION_STACKTRACE) String exceptionStackTrace) {
        
        String payloadString = new String(payload, StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        log.error("DLT Received Message. ID: {}, From Topic: {}, Reason: {}", id, originalTopic, exceptionMessage);

        DltMessage dltMessage = DltMessage.builder()
                .id(id)
                .originalTopic(originalTopic)
                .originalPartition(originalPartition)
                .originalOffset(originalOffset)
                .exceptionMessage(exceptionMessage)
                .exceptionStackTrace(exceptionStackTrace)
                .failedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .originalMessagePayload(payloadString)
                .build();
        
        dltMessages.put(id, dltMessage);
    }

    public Collection<DltMessage> getDltMessages() {
        return dltMessages.values();
    }

    public void deleteMessage(String id) {
        if (dltMessages.remove(id) != null) {
            log.info("Removed DLT message with ID: {}", id);
        } else {
            log.warn("Could not find DLT message with ID to delete: {}", id);
        }
    }

    public void redriveMessage(String id) throws JsonProcessingException {
        DltMessage dltMessage = dltMessages.get(id);
        if (dltMessage == null) {
            throw new IllegalArgumentException("No DLT message found with ID: " + id);
        }

        // De-serialize the original payload string back to an object
        Object originalPayload = objectMapper.readValue(dltMessage.getOriginalMessagePayload(), Object.class);

        log.info("Redriving message ID: {}. Sending to original topic: {}", id, dltMessage.getOriginalTopic());
        
        // Re-send the message to its original topic
        kafkaTemplate.send(dltMessage.getOriginalTopic(), originalPayload);

        // Remove it from our in-memory store after successful redrive
        dltMessages.remove(id);
    }
}