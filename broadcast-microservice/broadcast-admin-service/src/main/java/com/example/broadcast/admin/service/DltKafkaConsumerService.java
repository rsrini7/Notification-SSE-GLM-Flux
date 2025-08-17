// file: broadcast-microservice/broadcast-admin-service/src/main/java/com/example/broadcast/admin/service/DltKafkaConsumerService.java
package com.example.broadcast.admin.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.admin.DltMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.DltRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class DltKafkaConsumerService {

    private final ObjectMapper objectMapper;
    private final DltRepository dltRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastLifecycleService broadcastLifecycleService;

    // MODIFIED: The @KafkaListener annotation now points to the two new, simplified DLTs.
    @KafkaListener(
            topics = {
                "${broadcast.kafka.topic.name-orchestration}" + Constants.DLT_SUFFIX,
                "${broadcast.kafka.topic.name-worker-prefix}" + Constants.DLT_SUFFIX
            },
            groupId = "${broadcast.kafka.consumer.group-dlt}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void listenToDlt(
            @Payload MessageDeliveryEvent failedEvent,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.DLT_ORIGINAL_TOPIC) String originalTopic,
            @Header(KafkaHeaders.DLT_ORIGINAL_PARTITION) int originalPartition,
            @Header(KafkaHeaders.DLT_ORIGINAL_OFFSET) long originalOffset,
            @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage,
            @Header(KafkaHeaders.DLT_EXCEPTION_STACKTRACE) String exceptionStacktrace,
            Acknowledgment acknowledgment) {

        log.info("DLT Received Message. FailedEvent: {}, Key: {}, Original Topic: {}, Reason: {}.", failedEvent, key, originalTopic, exceptionMessage);

        String displayTitle;
        String payloadJson;

        try {
            payloadJson = objectMapper.writeValueAsString(failedEvent);
        } catch (JsonProcessingException e) {
            log.error("Critical: Could not re-serialize failed event for DLT storage.", e);
            payloadJson = "{\"error\":\"Could not serialize payload\"}";
        }

        if (failedEvent.getUserId() != null) {
            displayTitle = String.format("Failed event for user %s (Broadcast: %d)",
                    failedEvent.getUserId(), failedEvent.getBroadcastId());
            handleProcessingFailure(failedEvent);
        } else {
            displayTitle = String.format("Failed group broadcast event (Broadcast: %d)",
                    failedEvent.getBroadcastId());
            broadcastLifecycleService.failBroadcast(failedEvent.getBroadcastId());
            log.warn("Marked entire BroadcastMessage {} as FAILED due to DLT event.", failedEvent.getBroadcastId());
        }

        log.error("DLT Received Message. Key: {}, Topic: {}, Reason: {}.", key, originalTopic, displayTitle);
        DltMessage dltMessage = DltMessage.builder()
                .id(UUID.randomUUID().toString())
                .broadcastId(failedEvent.getBroadcastId())
                .originalKey(key)
                .originalTopic(originalTopic)
                .originalPartition(originalPartition)
                .originalOffset(originalOffset)
                .exceptionMessage(displayTitle)
                .exceptionStackTrace(exceptionStacktrace)
                .failedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .originalMessagePayload(payloadJson)
                .build();
        
         try {
            dltRepository.save(dltMessage);
            log.info("Saved new DLT message for broadcast ID: {}", failedEvent.getBroadcastId());
        } catch (DataIntegrityViolationException e) {
            log.warn("Ignoring duplicate DLT message for broadcast ID: {}. A record already exists.", failedEvent.getBroadcastId());
        }
                
        acknowledgment.acknowledge();
    }
    
    private void handleProcessingFailure(MessageDeliveryEvent event) {
        Optional<UserBroadcastMessage> existingMessageOpt = userBroadcastRepository.findByUserIdAndBroadcastId(event.getUserId(), event.getBroadcastId());

        if (existingMessageOpt.isPresent()) {
            UserBroadcastMessage userMessage = existingMessageOpt.get();
            userBroadcastRepository.updateDeliveryStatus(userMessage.getId(), Constants.DeliveryStatus.FAILED.name());
            log.info("Marked existing UserBroadcastMessage (ID: {}) as FAILED for user {} due to processing error.", userMessage.getId(), event.getUserId());
        } 
    }
}