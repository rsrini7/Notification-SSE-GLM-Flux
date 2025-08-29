package com.example.broadcast.admin.service;

import com.example.broadcast.admin.repository.DltRepository;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.admin.DltMessage;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.UserBroadcastMessage;
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

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DltKafkaConsumerService {

    private final ObjectMapper objectMapper;
    private final DltRepository dltRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastLifecycleService broadcastLifecycleService;
    private final BroadcastMapper broadcastMapper;

    @KafkaListener(
            topics = {
                "${broadcast.kafka.topic.name-orchestration}" + Constants.DLT_SUFFIX
            },
            groupId = "${broadcast.kafka.consumer.group-dlt}",
            containerFactory = "dltListenerContainerFactory"
    )
    @Transactional
    public void listenToDlt(
            @Payload(required = false) MessageDeliveryEvent failedEvent,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_TOPIC, required = false) String originalTopic,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_PARTITION, required = false) Integer originalPartition,
            @Header(name = KafkaHeaders.DLT_ORIGINAL_OFFSET, required = false) Long originalOffset,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) String exceptionMessage,
            @Header(name = KafkaHeaders.DLT_EXCEPTION_STACKTRACE, required = false) String exceptionStacktrace,
            Acknowledgment acknowledgment) {

            
        if (failedEvent == null) {
            log.info("Received tombstone record from DLT for key: {}. Acknowledging and discarding.", key);
            acknowledgment.acknowledge();
            return;
        }

        if(key == null){
            log.info("Received tombstone record from DLT for key is null. Acknowledging and discarding.");
            acknowledgment.acknowledge();
            return;
        }

        if(originalTopic == null){
            log.info("Received tombstone record from DLT for original topic is null. Acknowledging and discarding.");
            acknowledgment.acknowledge();
            return;
        }

        log.info("DLT Received Message. Failed Message: {}, Key: {}, Original Topic: {}, Reason: {}.\nStacktrace: {}", failedEvent.getMessage(), key, originalTopic, exceptionMessage, filterStackTrace(exceptionStacktrace));

        String displayTitle;
        String payloadJson;

        failedEvent.setErrorDetails(filterStackTrace(exceptionStacktrace));

        try {
            payloadJson = objectMapper.writeValueAsString(failedEvent);
        } catch (JsonProcessingException e) {
            log.error("CRITICAL: Could not parse DLT message failedEvent.", e);
            failedEvent = new MessageDeliveryEvent();
            failedEvent.setMessage("POISON PILL: UNABLE TO DESERIALIZE PAYLOAD");
            try{
                payloadJson = objectMapper.writeValueAsString(failedEvent);
            } catch (JsonProcessingException e2) {
                log.info("Ignorng the failed PoisonPill Object to Json Convertion issue .", key);
                acknowledgment.acknowledge();
                return;
            }
        }

        if (failedEvent.getUserId() != null) {
            displayTitle = String.format("Failed event for user %s (Broadcast: %d)",
                    failedEvent.getUserId(), failedEvent.getBroadcastId());
            handleProcessingFailure(failedEvent);
        } else {
            displayTitle = String.format("Failed group broadcast event (Broadcast: %d)",
                    failedEvent.getBroadcastId());
            broadcastLifecycleService.failBroadcast(failedEvent);
            log.warn("Marked entire BroadcastMessage {} as FAILED due to DLT event.", failedEvent.getBroadcastId());
        }

        log.error("DLT Received Message. Key: {}, Topic: {}, DisplayTitle: {}.", key, originalTopic, displayTitle);
        DltMessage dltMessage = broadcastMapper.toDltMessage(
                failedEvent, key, originalTopic, originalPartition, originalOffset,
                displayTitle, exceptionStacktrace, payloadJson
            );
        
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

    private String filterStackTrace(String fullStackTrace) {
        if (fullStackTrace == null || fullStackTrace.isEmpty()) {
            return "Stack trace not available.";
        }

        String[] lines = fullStackTrace.split("\n");
        StringBuilder filteredTrace = new StringBuilder();
        
        for (String line : lines) {
            String trimmedLine = line.trim();
            // Include the "Caused by" lines and any lines from our application code
            if (trimmedLine.startsWith("Caused by:") || trimmedLine.contains("com.example.broadcast")) {
                filteredTrace.append(trimmedLine).append("\n");
            }
        }

        if (filteredTrace.length() == 0) {
            return lines[0]; // Return the top-level exception message if no relevant lines found
        }

        return filteredTrace.toString().trim();
    }
}