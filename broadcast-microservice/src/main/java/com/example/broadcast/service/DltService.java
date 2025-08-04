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
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

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
            topics = "${broadcast.kafka.topic.name:broadcast-events}" + Constants.DLT_SUFFIX,
            groupId = "${broadcast.kafka.consumer.dlt-group-id:broadcast-dlt-group}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void listenToDlt(
            @Payload byte[] payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.DLT_ORIGINAL_TOPIC) String originalTopic,
            @Header(KafkaHeaders.DLT_ORIGINAL_PARTITION) int originalPartition,
            @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET) long originalOffset,
            // START OF CHANGE: We now also receive the full exception object
            @Header(KafkaHeaders.DLT_EXCEPTION_FQCN) String exceptionFqcn,
            @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage,
            @Header(KafkaHeaders.DLT_EXCEPTION_STACKTRACE) String exceptionStacktrace) {
        
        String payloadString = new String(payload, StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        
        // START OF CHANGE: Build a more informative title
        String displayTitle = "Failed to process message"; // Default title
        try {
            MessageDeliveryEvent event = objectMapper.readValue(payloadString, MessageDeliveryEvent.class);
            if (event.getUserId() != null && event.getBroadcastId() != null) {
                displayTitle = String.format("Failed event '%s' for user %s (Broadcast: %d)",
                    event.getEventType(), event.getUserId(), event.getBroadcastId());
            }
        } catch (JsonProcessingException e) {
            log.warn("Could not parse DLT message payload to extract details for title.");
            displayTitle = exceptionMessage; // Fallback to the raw exception message
        }
        // END OF CHANGE
        
        log.error("DLT Received Message. Saving to database with ID: {}, From Topic: {}, Reason: {}", id, originalTopic, displayTitle);
        DltMessage dltMessage = DltMessage.builder()
                .id(id)
                .originalTopic(originalTopic)
                .originalPartition(originalPartition)
                .originalOffset(originalOffset)
                .exceptionMessage(displayTitle) // Use our new user-friendly title
                .failedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .originalMessagePayload(payloadString)
                .build();
        dltRepository.save(dltMessage);
    }
    
    public Collection<DltMessage> getDltMessages() {
        return dltRepository.findAll();
    }

    @Transactional
    public void deleteMessage(String id) {
        dltRepository.deleteById(id);
        log.info("Deleted DLT message with ID: {}", id);
    }

    @Transactional
    public void redriveMessage(String id) throws JsonProcessingException {
        DltMessage dltMessage = dltRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No DLT message found with ID: " + id));
        MessageDeliveryEvent originalPayload = objectMapper.readValue(dltMessage.getOriginalMessagePayload(), MessageDeliveryEvent.class);

        prepareDatabaseForRedrive(originalPayload);

        log.info("Redriving message ID: {}. Sending to original topic: {}", id, dltMessage.getOriginalTopic());
        
        kafkaTemplate.send(dltMessage.getOriginalTopic(), originalPayload.getUserId(), originalPayload);
        dltRepository.deleteById(id);
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
    public void purgeMessage(String id) throws JsonProcessingException {
        DltMessage dltMessage = dltRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No DLT message found with ID: " + id));
        dltRepository.deleteById(id);
        log.info("Deleted DLT message with ID: {} from the database.", id);
        
        String messageKey = dltMessage.getId();
        String dltTopicName = dltMessage.getOriginalTopic() + Constants.DLT_SUFFIX;
        
        kafkaTemplate.send(dltTopicName, messageKey, null);
        log.info("Sent tombstone message to Kafka topic {} with key {} to purge the message.", dltTopicName, messageKey);
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
            String messageKey = dltMessage.getId();
            String dltTopicName = dltMessage.getOriginalTopic() + Constants.DLT_SUFFIX;
            kafkaTemplate.send(dltTopicName, messageKey, null);
        }

        dltRepository.deleteAll();
        log.info("Purged all DLT messages from the database and sent tombstone records to Kafka.");
    }
}