package com.example.broadcast.admin.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

@Service
@Slf4j
@Profile("!checkpoint-build")
public class OutboxPollingService {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter pollRunsCounter;

    public OutboxPollingService(OutboxRepository outboxRepository,
                                KafkaTemplate<String, Object> kafkaTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) { // Add MeterRegistry
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.pollRunsCounter = meterRegistry.counter("broadcast.outbox.poll.runs.total");
    }

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
                
                // Make the Kafka send synchronous by calling .get()
                // This will block until Kafka acknowledges the message or throws an exception.
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), payload).get();

            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize outbox event payload for event ID {}", event.getId(), e);
                // This is a critical, likely unrecoverable error for this message.
                // In a real system, you might move it to a separate "poison pill" table.
                // For now, we will let the transaction roll back and retry later.
                throw new RuntimeException("Failed to deserialize event payload", e);
            } catch (InterruptedException | ExecutionException e) {
                log.error("Failed to send outbox event {} to Kafka. The transaction will be rolled back.", event.getId(), e);
                // Throwing a runtime exception ensures the transaction is rolled back,
                // so the event remains in the outbox for the next polling cycle.
                throw new RuntimeException("Kafka send failed", e);
            }
        }
        
        // This code will only be reached if ALL Kafka sends in the batch were successful.
        List<UUID> processedIds = events.stream().map(OutboxEvent::getId).collect(Collectors.toList());
        outboxRepository.deleteByIds(processedIds);
        log.trace("Successfully published and deleted {} events from outbox.", processedIds.size());
        pollRunsCounter.increment(); // Increment the counter on every successful run
    }
}