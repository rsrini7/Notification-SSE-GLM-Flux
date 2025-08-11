package com.example.broadcast.admin.service;

import com.example.broadcast.admin.dto.RedriveAllResult;
import com.example.broadcast.admin.dto.RedriveFailureDetail;
import com.example.broadcast.admin.dto.DltMessage;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.DltRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.shared.util.Constants.DeliveryStatus;
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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
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
    private final MessageStatusService messageStatusService;
    private final TestingConfigurationService testingConfigurationService;
    private final BroadcastLifecycleService broadcastLifecycleService;

    @KafkaListener(
            topics = {
                "${broadcast.kafka.topic.name.selected:broadcast-events-selected}" + Constants.DLT_SUFFIX,
                "${broadcast.kafka.topic.name.group:broadcast-events-group}" + Constants.DLT_SUFFIX
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

        // NEW LOGIC: Differentiate between a user-specific and a group failure.
        if (failedEvent.getUserId() != null) {
            // --- PATH FOR "SELECTED" USERS ---
            displayTitle = String.format("Failed event for user %s (Broadcast: %d)",
                    failedEvent.getUserId(), failedEvent.getBroadcastId());
            // This marks the specific user's message record as FAILED.
            handleProcessingFailure(failedEvent);
        } else {
            // --- NEW PATH FOR "ALL" / "ROLE" USERS ---
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
                .originalKey(key)
                .originalTopic(originalTopic)
                .originalPartition(originalPartition)
                .originalOffset(originalOffset)
                .exceptionMessage(displayTitle)
                .exceptionStackTrace(exceptionStacktrace)
                .failedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .originalMessagePayload(payloadJson)
                .build();
        dltRepository.save(dltMessage);

        // testingConfigurationService.clearFailureMark(failedEvent.getBroadcastId());
        // log.info("Cleared DLT failure mark for broadcast ID: {}", failedEvent.getBroadcastId());

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

    public Collection<DltMessage> getDltMessages() {
        return dltRepository.findAll();
    }

    @Transactional
    public void redriveMessage(String id) throws JsonProcessingException {
        DltMessage dltMessage = dltRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No DLT message found with ID: " + id));

        // Step 1: Prepare the database synchronously in a new transaction.
        prepareDatabaseForRedrive(dltMessage);

        // Step 2: Resend the original message and clean up.
        log.info("Redriving message ID: {}. Sending original message back to topic: {}", id, dltMessage.getOriginalTopic());
        try {
            MessageDeliveryEvent originalPayload = objectMapper.readValue(dltMessage.getOriginalMessagePayload(), MessageDeliveryEvent.class);
            kafkaTemplate.send(dltMessage.getOriginalTopic(), originalPayload.getUserId(), originalPayload).get();
            
            testingConfigurationService.clearFailureMark(originalPayload.getBroadcastId());
            log.info("Cleared DLT failure mark for broadcast ID: {}", originalPayload.getBroadcastId());

            String dltTopicName = dltMessage.getOriginalTopic() + Constants.DLT_SUFFIX;
            kafkaTemplate.send(dltTopicName, dltMessage.getOriginalKey(), null).get();
            
            dltRepository.deleteById(id);
            log.info("Successfully redriven and purged DLT message with ID: {}", id);
        } catch (InterruptedException | ExecutionException e) {
            log.error("Failed to redrive message with ID: {}. It will remain in the DLT.", id, e);
            throw new RuntimeException("Failed to send message to Kafka during redrive", e);
        }
    }
    
    /**
     * Prepares the database for a message redrive by validating state and resetting the
     * original message's status to PENDING. This is a direct, synchronous call.
     */
    private void prepareDatabaseForRedrive(DltMessage dltMessage) throws JsonProcessingException {
        MessageDeliveryEvent payload = objectMapper.readValue(dltMessage.getOriginalMessagePayload(), MessageDeliveryEvent.class);

        BroadcastMessage parentBroadcast = broadcastRepository.findById(payload.getBroadcastId())
            .orElseThrow(() -> new IllegalStateException("Cannot redrive: original broadcast (ID: " + payload.getBroadcastId() + ") has been deleted."));
            
        if (!Constants.BroadcastStatus.ACTIVE.name().equals(parentBroadcast.getStatus()) && 
            !Constants.BroadcastStatus.FAILED.name().equals(parentBroadcast.getStatus())) {
            throw new IllegalStateException("Cannot redrive message because the original broadcast (ID: " + payload.getBroadcastId() + ") is " + parentBroadcast.getStatus() + ".");
        }

        // 2. (NEW) If the broadcast was FAILED, set it back to ACTIVE.
        if (Constants.BroadcastStatus.FAILED.name().equals(parentBroadcast.getStatus())) {
            log.warn("Re-activating FAILED broadcast {} for DLT redrive.", parentBroadcast.getId());
            broadcastRepository.updateStatus(parentBroadcast.getId(), Constants.BroadcastStatus.ACTIVE.name());
        }

        if (payload.getUserId() != null) {
            userBroadcastRepository.findByUserIdAndBroadcastId(
                payload.getUserId(), payload.getBroadcastId()
            ).ifPresent(existingMessage -> {
                messageStatusService.resetMessageForRedrive(existingMessage.getId());
            });
        }

    }
    
    public RedriveAllResult redriveAllMessages() {
        List<DltMessage> messagesToRedrive = dltRepository.findAll();
        if (messagesToRedrive.isEmpty()) {
            log.info("No DLT messages to redrive.");
            return RedriveAllResult.builder().totalMessages(0).successCount(0).failureCount(0).failures(new ArrayList<>()).build();
        }

        log.info("Attempting to redrive all {} messages from the DLT.", messagesToRedrive.size());
        int successCount = 0;
        List<RedriveFailureDetail> failures = new ArrayList<>();

        for (DltMessage dltMessage : messagesToRedrive) {
            try {
                // The redriveMessage method is transactional, so each attempt is atomic.
                redriveMessage(dltMessage.getId());
                successCount++;
            } catch (Exception e) {
                // Instead of just logging, we now record the failure details.
                failures.add(new RedriveFailureDetail(dltMessage.getId(), e.getMessage()));
                log.error("Failed to redrive DLT message with ID: {}. Reason: {}", dltMessage.getId(), e.getMessage());
            }
        }

        log.info("Finished redriving all DLT messages. Success: {}, Failures: {}", successCount, failures.size());

        // Return the detailed result object.
        return RedriveAllResult.builder()
                .totalMessages(messagesToRedrive.size())
                .successCount(successCount)
                .failureCount(failures.size())
                .failures(failures)
                .build();
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