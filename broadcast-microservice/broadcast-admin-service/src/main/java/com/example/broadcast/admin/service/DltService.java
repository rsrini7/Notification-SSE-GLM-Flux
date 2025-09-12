package com.example.broadcast.admin.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.service.KafkaListnerHelper;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.util.Constants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.broadcast.admin.dto.DltMessage;
import com.example.broadcast.admin.dto.RedriveAllResult;
import com.example.broadcast.admin.dto.RedriveFailureDetail;
import com.example.broadcast.admin.repository.DltRepository;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
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
    private final KafkaListnerHelper kafkaListnerHelper ;
    
    public Collection<DltMessage> getDltMessages() {
        return dltRepository.findAllByOrderByFailedAtDesc();
    }

    @Transactional
    public void redriveMessage(String id) throws JsonProcessingException {
        DltMessage dltMessage = dltRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No DLT message found with ID: " + id));

        prepareDatabaseForRedrive(dltMessage);

        log.info("Redriving message ID: {}. Sending original message back to topic: {}", id, dltMessage.getOriginalTopic());
        try {
            MessageDeliveryEvent originalPayload = objectMapper.readValue(dltMessage.getOriginalMessagePayload(), MessageDeliveryEvent.class);
            
            // Use the original key (broadcastId) instead of the potentially null userId.
            String messageKey = dltMessage.getOriginalKey();
            kafkaTemplate.send(dltMessage.getOriginalTopic(), messageKey, originalPayload).get();

            log.info("Cleared DLT failure mark for broadcast ID: {}", originalPayload.getBroadcastId());

            String dltTopicName = resolveDltTopicName(dltMessage.getOriginalTopic());
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
        List<DltMessage> messagesToRedrive = dltRepository.findAllByOrderByFailedAtDesc();
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
        String dltTopicName = resolveDltTopicName(dltMessage.getOriginalTopic());
        kafkaTemplate.send(dltTopicName, dltMessage.getOriginalKey(), null);
        dltRepository.deleteById(id);
        log.info("Purged DLT message with ID: {} and sent tombstone to topic {} with key {}.", id, dltTopicName, dltMessage.getOriginalKey());
    }

    @Transactional
    public void purgeAllMessages() {
        Collection<DltMessage> messagesToPurge = dltRepository.findAllByOrderByFailedAtDesc();
        if (messagesToPurge.isEmpty()) {
            log.info("No DLT messages to purge.");
            return;
        }
        log.info("Purging all {} messages from the DLT.", messagesToPurge.size());
        for (DltMessage dltMessage : messagesToPurge) {
            String dltTopicName = resolveDltTopicName(dltMessage.getOriginalTopic());
            kafkaTemplate.send(dltTopicName, dltMessage.getOriginalKey(), null);
        }
        dltRepository.deleteAll();
        log.info("Purged all DLT messages from the database and sent tombstone records to Kafka.");
    }

     /**
     * Determines the correct DLT topic name based on the original topic.
     * This logic now mirrors the DeadLetterPublishingRecoverer.
     * @param originalTopic The topic where the message originally failed.
     * @return The correct DLT topic name.
     */
    private String resolveDltTopicName(String originalTopic) {
        // This logic should not be recursive. It should always derive the DLT name
        // from the primary orchestration topic, not from another DLT.
        return kafkaListnerHelper.getOrchestrationDltTopic();
    }
}