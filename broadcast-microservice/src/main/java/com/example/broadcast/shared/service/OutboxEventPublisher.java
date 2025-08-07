package com.example.broadcast.shared.service;

import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List; // Import List
import java.util.UUID;

/**
 * A dedicated, generic service responsible for publishing events using the
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
     * Serializes any given payload object and saves it as an OutboxEvent.
     * This method must be called from within an existing transaction.
     *
     * @param payload       The event data object to publish.
     * @param aggregateId   A key identifier for the event, often used as the Kafka message key.
     * @param eventType     A string identifying the type of event.
     * @param topicName     The Kafka topic to which the event will be sent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Object payload, String aggregateId, String eventType, String topicName) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            OutboxEvent outboxEvent = OutboxEvent.builder()
                    .id(UUID.randomUUID())
                    .aggregateType(payload.getClass().getSimpleName())
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .topic(topicName)
                    .payload(payloadJson)
                    .build();
            outboxRepository.save(outboxEvent);
        } catch (JsonProcessingException e) {
            log.error("Critical: Failed to serialize event payload for outbox. Event type {} for aggregate {} will not be published.", eventType, aggregateId, e);
            throw new RuntimeException("Failed to serialize event payload for outbox.", e);
        }
    }

    /**
     * Persists a list of OutboxEvent objects in a single batch operation.
     * This method must be called from within an existing transaction.
     * @param events The list of fully constructed OutboxEvent objects to save.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishBatch(List<OutboxEvent> events) {
        if (events != null && !events.isEmpty()) {
            outboxRepository.batchSave(events);
        }
    }
}