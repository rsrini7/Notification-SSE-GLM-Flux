package com.example.broadcast.service;

import com.example.broadcast.dto.DltMessage;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.DltRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.util.Constants;
import com.example.broadcast.util.Constants.DeliveryStatus;
import com.example.broadcast.util.Constants.ReadStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
@RequiredArgsConstructor
public class DltService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DltRepository dltRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;

    @KafkaListener(
            topics = {
                "${broadcast.kafka.topic.name.all:broadcast-events-all}" + Constants.DLT_SUFFIX,
                "${broadcast.kafka.topic.name.selected:broadcast-events-selected}" + Constants.DLT_SUFFIX
            },
            groupId = "${broadcast.kafka.consumer.dlt-group-id:broadcast-dlt-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void listenToDlt(
            @Payload byte[] payload,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.DLT_ORIGINAL_TOPIC) String originalTopic,
            @Header(KafkaHeaders.DLT_ORIGINAL_PARTITION) int originalPartition,
            @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET) long originalOffset,
            @Header(KafkaHeaders.DLT_EXCEPTION_FQCN) String exceptionFqcn,
            @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage,
            @Header(KafkaHeaders.DLT_EXCEPTION_STACKTRACE) String exceptionStacktrace,
            Acknowledgment acknowledgment) {
        
        String payloadString = new String(payload, StandardCharsets.UTF_8);
        MessageDeliveryEvent failedEvent = null;
        String displayTitle = "Failed to process message";

        try {
            failedEvent = objectMapper.readValue(payloadString, MessageDeliveryEvent.class);
            if (failedEvent.getUserId() != null && failedEvent.getBroadcastId() != null) {
                // Step 1: Update original message status to FAILED
                handleProcessingFailure(failedEvent);
                displayTitle = String.format("Failed event '%s' for user %s (Broadcast: %d)",
                    failedEvent.getEventType(), failedEvent.getUserId(), failedEvent.getBroadcastId());
            }
        } catch (JsonProcessingException e) {
            log.warn("Could not parse DLT message payload to extract details.", e);
            displayTitle = exceptionMessage;
        }
        
        // Step 2: Save the record for the DLT UI
        log.error("DLT Received Message. Saving to database. Original Key: {}, From Topic: {}, Reason: {}", key, originalTopic, displayTitle);
        DltMessage dltMessage = DltMessage.builder()
                .id(UUID.randomUUID().toString())
                .originalKey(key)
                .originalTopic(originalTopic)
                .originalPartition(originalPartition)
                .originalOffset(originalOffset)
                .exceptionMessage(displayTitle)
                .failedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .originalMessagePayload(payloadString)
                .build();
        dltRepository.save(dltMessage);

        // Step 3: Acknowledge the DLT message
        acknowledgment.acknowledge();
    }
    
    // This method is now private and called from within the listener's transaction
    private void handleProcessingFailure(MessageDeliveryEvent event) {
        if (event == null || event.getUserId() == null || event.getBroadcastId() == null) {
            log.error("Cannot handle processing failure: event or its key fields are null.");
            return;
        }

        userBroadcastRepository.findByUserIdAndBroadcastId(event.getUserId(), event.getBroadcastId())
            .ifPresent(userMessage -> {
                if (!DeliveryStatus.FAILED.name().equals(userMessage.getDeliveryStatus())) {
                    userBroadcastRepository.updateDeliveryStatus(userMessage.getId(), DeliveryStatus.FAILED.name());
                    log.info("Marked UserBroadcastMessage (ID: {}) as FAILED for user {} due to processing error.", userMessage.getId(), event.getUserId());
                }
            });
    }

    public Collection<DltMessage> getDltMessages() {
        return dltRepository.findAll();
    }

    @Transactional
    public void redriveMessage(String id) throws JsonProcessingException {
        DltMessage dltMessage = dltRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No DLT message found with ID: " + id));
        MessageDeliveryEvent originalPayload = objectMapper.readValue(dltMessage.getOriginalMessagePayload(), MessageDeliveryEvent.class);

        prepareDatabaseForRedrive(originalPayload);

        log.info("Redriving message ID: {}. Sending to original topic: {}", id, dltMessage.getOriginalTopic());
        
        try {
            kafkaTemplate.send(dltMessage.getOriginalTopic(), originalPayload.getUserId(), originalPayload).get();
            
            String dltTopicName = dltMessage.getOriginalTopic() + Constants.DLT_SUFFIX;
            kafkaTemplate.send(dltTopicName, dltMessage.getOriginalKey(), null).get();
            
            dltRepository.deleteById(id);
            log.info("Successfully redriven and purged DLT message with ID: {}", id);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to redrive message with ID: {}. It will remain in the DLQ.", id, e);
            throw new RuntimeException("Failed to send message to Kafka during redrive", e);
        }
    }
    
    public void redriveAllMessages() {
        List<DltMessage> messagesToRedrive = dltRepository.findAll();
        if (messagesToRedrive.isEmpty()) {
            log.info("No DLT messages to redrive.");
            return;
        }

        log.info("Attempting to redrive all {} messages from the DLT.", messagesToRedrive.size());
        int successCount = 0;
        int failureCount = 0;

        for (DltMessage dltMessage : messagesToRedrive) {
            try {
                redriveMessage(dltMessage.getId());
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.error("Failed to redrive DLT message with ID: {}. It will remain in the DLT. Reason: {}", dltMessage.getId(), e.getMessage());
            }
        }
        log.info("Finished redriving all DLT messages. Success: {}, Failures: {}", successCount, failureCount);
    }

    private void prepareDatabaseForRedrive(MessageDeliveryEvent payload) {
        BroadcastMessage parentBroadcast = broadcastRepository.findById(payload.getBroadcastId())
            .orElseThrow(() -> new IllegalStateException("Cannot redrive message because the original broadcast (ID: " + payload.getBroadcastId() + ") has been deleted."));

        if (!Constants.BroadcastStatus.ACTIVE.name().equals(parentBroadcast.getStatus())) {
            log.error("Cannot redrive message for broadcast ID {}. The broadcast is no longer ACTIVE (current status: {}).", payload.getBroadcastId(), parentBroadcast.getStatus());
            throw new IllegalStateException("Cannot redrive message because the original broadcast (ID: " + payload.getBroadcastId() + ") is " + parentBroadcast.getStatus() + ".");
        }

        Optional<UserBroadcastMessage> existingMessage = userBroadcastRepository.findByUserIdAndBroadcastId(
            payload.getUserId(), payload.getBroadcastId()
        );

        if (existingMessage.isPresent()) {
            UserBroadcastMessage message = existingMessage.get();
            userBroadcastRepository.updateStatusToPending(message.getId());
            log.info("Reset existing UserBroadcastMessage (ID: {}) to PENDING for redrive.", message.getId());
        } else {
            UserBroadcastMessage newMessage = UserBroadcastMessage.builder()
                    .broadcastId(payload.getBroadcastId())
                    .userId(payload.getUserId())
                    .deliveryStatus(DeliveryStatus.PENDING.name())
                    .readStatus(ReadStatus.UNREAD.name())
                    .createdAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .updatedAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .build();
            userBroadcastRepository.save(newMessage);
            log.info("Created new UserBroadcastMessage for redrive for user {} and broadcast {}.", payload.getUserId(), payload.getBroadcastId());
        }
    }
    
    @Transactional
    public void purgeMessage(String id) {
        DltMessage dltMessage = dltRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No DLT message found with ID: " + id));
        
        String dltTopicName = dltMessage.getOriginalTopic() + Constants.DLT_SUFFIX;
        kafkaTemplate.send(dltTopicName, dltMessage.getOriginalKey(), null);
        
        dltRepository.deleteById(id);
        log.info("Purged DLT message with ID: {} and sent tombstone to topic {} with key {}.", id, dltTopicName, dltMessage.getOriginalKey());
    }

    @Transactional
    public void purgeAllMessages() {
        Collection<DltMessage> messagesToPurge = dltRepository.findAll();
        if (messagesToPurge.isEmpty()) {
            log.info("No DLT messages to purge.");
            return;
        }

        log.info("Purging all {} messages from the DLT.", messagesToPurge.size());

        for (DltMessage dltMessage : messagesToPurge) {
            String dltTopicName = dltMessage.getOriginalTopic() + Constants.DLT_SUFFIX;
            kafkaTemplate.send(dltTopicName, dltMessage.getOriginalKey(), null);
        }

        dltRepository.deleteAll();
        log.info("Purged all DLT messages from the database and sent tombstone records to Kafka.");
    }
}