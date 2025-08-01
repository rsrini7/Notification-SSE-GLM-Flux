package com.example.broadcast.service;

import com.example.broadcast.dto.DltMessage;
import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.model.BroadcastMessage;
import com.example.broadcast.model.UserBroadcastMessage;
import com.example.broadcast.repository.BroadcastRepository;
import com.example.broadcast.repository.DltRepository;
import com.example.broadcast.repository.UserBroadcastRepository;
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
import com.example.broadcast.util.Constants;

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
            @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage) {
        
        String payloadString = new String(payload, StandardCharsets.UTF_8);
        String id = UUID.randomUUID().toString();
        log.error("DLT Received Message. Saving to database with ID: {}, From Topic: {}, Reason: {}", id, originalTopic, exceptionMessage);
        
        DltMessage dltMessage = DltMessage.builder()
                .id(id)
                .originalTopic(originalTopic)
                .originalPartition(originalPartition)
                .originalOffset(originalOffset)
                .exceptionMessage(exceptionMessage)
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
        Optional<BroadcastMessage> parentBroadcast = broadcastRepository.findById(payload.getBroadcastId());
        if (parentBroadcast.isEmpty()) {
            log.error("Cannot redrive message for broadcast ID {}. The original broadcast has been deleted.", payload.getBroadcastId());
            throw new IllegalStateException("Cannot redrive message because the original broadcast (ID: " + payload.getBroadcastId() + ") has been deleted.");
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
    
    // NEW: Implements the purge logic.
    @Transactional
    public void purgeMessage(String id) throws JsonProcessingException {
        DltMessage dltMessage = dltRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("No DLT message found with ID: " + id));

        // Delete from the database first.
        dltRepository.deleteById(id);
        log.info("Deleted DLT message with ID: {} from the database.", id);

        // Send a tombstone message to the DLT to purge it from Kafka's log.
        // We need a key for compaction to work. Let's use the DB message ID as the key.
        String messageKey = dltMessage.getId();
        String dltTopicName = dltMessage.getOriginalTopic() + Constants.DLT_SUFFIX;
        
        kafkaTemplate.send(dltTopicName, messageKey, null);
        log.info("Sent tombstone message to Kafka topic {} with key {} to purge the message.", dltTopicName, messageKey);
    }
}