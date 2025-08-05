package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.OutboxEvent;
import com.example.broadcast.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OutboxPollingService {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 2000) // Poll every 2 seconds
    @Transactional
    public void pollAndPublishEvents() {
        List<OutboxEvent> events = outboxRepository.findAndLockUnprocessedEvents(100);

        if (events.isEmpty()) {
            return;
        }

        log.trace("Found {} events in outbox to publish.", events.size());

        for (OutboxEvent event : events) {
            try {
                MessageDeliveryEvent payload = objectMapper.readValue(event.getPayload(), MessageDeliveryEvent.class);
                // Use the topic from the event itself
                kafkaTemplate.send(event.getTopic(), payload.getUserId(), payload)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                log.error("Failed to send outbox event {} to Kafka", event.getId(), ex);
                                // The transaction will roll back, and the event will be retried later.
                                throw new RuntimeException("Kafka send failed", ex);
                            }
                        });
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize outbox event payload for event ID {}", event.getId(), e);
                // Handle potentially unrecoverable message, maybe move to a dead-letter table
            }
        }
        
        // If all sends are successful, delete the events from the outbox
        List<UUID> processedIds = events.stream().map(OutboxEvent::getId).collect(Collectors.toList());
        outboxRepository.deleteByIds(processedIds);
        log.trace("Successfully published and deleted {} events from outbox.", processedIds.size());
    }
}