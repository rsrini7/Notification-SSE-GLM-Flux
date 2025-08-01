package com.example.broadcast.service;

import com.example.broadcast.dto.DltMessage;
import com.example.broadcast.repository.DltRepository; // Import the new repository
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
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
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DltService {

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final DltRepository dltRepository; // Inject the repository

    @KafkaListener(
            topics = "${broadcast.kafka.topic.name:broadcast-events}.DLT",
            groupId = "broadcast-dlt-group"
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
        
        // Save the failed message to the database instead of an in-memory map
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
        DltMessage dltMessage;
        try {
            dltMessage = dltRepository.findById(id);
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalArgumentException("No DLT message found with ID: " + id);
        }

        Object originalPayload = objectMapper.readValue(dltMessage.getOriginalMessagePayload(), Object.class);

        log.info("Redriving message ID: {}. Sending to original topic: {}", id, dltMessage.getOriginalTopic());
        
        kafkaTemplate.send(dltMessage.getOriginalTopic(), originalPayload);

        // After successfully re-sending, delete it from the database.
        dltRepository.deleteById(id);
    }
}