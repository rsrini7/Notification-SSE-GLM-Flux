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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import com.example.broadcast.shared.util.Constants.DeliveryStatus;

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

    @KafkaListener(
            topics = {
                "${broadcast.kafka.topic.name.selected:broadcast-events-selected}" + Constants.DLT_SUFFIX,
                "${broadcast.kafka.topic.name.group.orchestration:broadcast-group-orchestration}" + Constants.DLT_SUFFIX,
                "${broadcast.kafka.topic.name.user.group:broadcast-user-events-group}" + Constants.DLT_SUFFIX,
                "${broadcast.kafka.topic.name.actions.orchestration:broadcast-actions-orchestration}" + Constants.DLT_SUFFIX,
                "${broadcast.kafka.topic.name.user.actions:broadcast-user-actions}" + Constants.DLT_SUFFIX
            },
            groupId = "${broadcast.kafka.consumer-dlt-group-id:broadcast-dlt-group}",
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

        // Differentiate between a user-specific and a group failure.
        if (failedEvent.getUserId() != null) {
            // FOR "SELECTED" USERS ---
            displayTitle = String.format("Failed event for user %s (Broadcast: %d)",
                    failedEvent.getUserId(), failedEvent.getBroadcastId());
            // This marks the specific user's message record as FAILED.
            handleProcessingFailure(failedEvent);
        } else {
            // FOR "ALL" / "ROLE" USERS ---
            displayTitle = String.format("Failed group broadcast event (Broadcast: %d)",
                    failedEvent.getBroadcastId());
            // Call the new, separately transacted method to update the status
            broadcastLifecycleService.failBroadcast(failedEvent.getBroadcastId());
            log.warn("Marked entire BroadcastMessage {} as FAILED due to DLT event.", failedEvent.getBroadcastId());
        }

        // This part is now common for both paths
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
            // This is the main change: wrap the save in a try-catch block
            dltRepository.save(dltMessage);
            log.info("Saved new DLT message for broadcast ID: {}", failedEvent.getBroadcastId());
        } catch (DataIntegrityViolationException e) {
            // This is the expected exception for the 2nd and 3rd pods.
            // It means a DLT record for this broadcastId already exists.
            log.warn("Ignoring duplicate DLT message for broadcast ID: {}. A record already exists.", failedEvent.getBroadcastId());
        }
                
        acknowledgment.acknowledge();
    }
    
    private void handleProcessingFailure(MessageDeliveryEvent event) {
        Optional<UserBroadcastMessage> existingMessageOpt = userBroadcastRepository.findByUserIdAndBroadcastId(event.getUserId(), event.getBroadcastId());

        if (existingMessageOpt.isPresent()) {
            // This is the existing logic for "SELECTED" users, which is correct.
            UserBroadcastMessage userMessage = existingMessageOpt.get();
            userBroadcastRepository.updateDeliveryStatus(userMessage.getId(), DeliveryStatus.FAILED.name());
            log.info("Marked existing UserBroadcastMessage (ID: {}) as FAILED for user {} due to processing error.", userMessage.getId(), event.getUserId());
        } 
    }
}