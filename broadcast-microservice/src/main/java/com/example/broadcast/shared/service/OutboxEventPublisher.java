package com.example.broadcast.shared.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * A dedicated service responsible for publishing events using the
 * transactional outbox pattern. This centralizes serialization and persistence
 * logic for all outgoing Kafka events.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Serializes a payload and saves it as an OutboxEvent within the current transaction.
     * This method is marked with Propagation.MANDATORY to ensure it's always called
     * from within an existing transaction, which is the core requirement of the outbox pattern.
     *
     * @param payload   The event data to publish.
     * @param topicName The Kafka topic to which the event will be sent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(MessageDeliveryEvent payload, String topicName) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("broadcast")
                    .aggregateId(payload.getUserId())
                    .eventType(payload.getEventType())
                    .topic(topicName)
                    .payload(payloadJson)
                    .build();
            outboxRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Critical: Failed to serialize event payload for outbox. Event for user {} will not be published.", payload.getUserId(), e);
            throw new RuntimeException("Failed to serialize event payload for outbox.", e);
        }
    }
}