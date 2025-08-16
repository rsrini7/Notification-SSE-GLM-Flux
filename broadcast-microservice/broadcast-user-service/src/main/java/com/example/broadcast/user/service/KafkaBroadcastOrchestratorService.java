// file: broadcast-microservice/broadcast-user-service/src/main/java/com/example/broadcast/user/service/KafkaBroadcastOrchestratorService.java
package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.model.OutboxEvent;
import com.example.broadcast.shared.repository.UserBroadcastTargetRepository;
import com.example.broadcast.shared.service.OutboxEventPublisher;
import com.example.broadcast.shared.service.cache.CacheService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class KafkaBroadcastOrchestratorService {

    private final UserBroadcastTargetRepository userBroadcastTargetRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final CacheService cacheService; // This dependency is needed to look up user connections

    @KafkaListener(
        topics = "${broadcast.kafka.topic.name-orchestration}",
        groupId = "${broadcast.kafka.consumer.group-orchestration}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void orchestrateBroadcastEvents(MessageDeliveryEvent event, 
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset
            ,Acknowledgment acknowledgment) {

        log.info("Orchestration event received for type '{}' on broadcast ID: {}. [Topic: {}, Partition: {}, Offset: {}]", event.getEventType(), event.getBroadcastId(), topic, partition, offset);

        List<String> allTargetedUsers = userBroadcastTargetRepository.findUserIdsByBroadcastId(event.getBroadcastId());

        if (!allTargetedUsers.isEmpty()) {
            log.info("Scattering {} user-specific '{}' events to worker topics", allTargetedUsers.size(), event.getEventType());
            List<OutboxEvent> eventsToPublish = allTargetedUsers.stream()
                .map(userId -> {
                    UserConnectionInfo connectionInfo = cacheService.getUserConnectionInfo(userId);
                    if (connectionInfo != null && connectionInfo.getPodId() != null) {
                        String topicName = appProperties.getKafka().getTopic().getNameWorkerPrefix() + connectionInfo.getPodId();
                        // CORRECTED: Passing the original event to the helper method
                        return createWorkerOutboxEvent(event, userId, topicName);
                    } else {
                        cacheService.cachePendingEvent(event.toBuilder().userId(userId).build());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            
            if (!eventsToPublish.isEmpty()) {
                outboxEventPublisher.publishBatch(eventsToPublish);
            }
        }
        
        acknowledgment.acknowledge();
    }

    // CORRECTED: Method signature now correctly accepts the parent MessageDeliveryEvent.
    private OutboxEvent createWorkerOutboxEvent(MessageDeliveryEvent originalEvent, String userId, String topicName) {
        // Create the new user-specific payload based on the original event.
        MessageDeliveryEvent userSpecificEvent = originalEvent.toBuilder()
                .eventId(UUID.randomUUID().toString()) // Give the new event its own unique ID
                .userId(userId)
                .build();
        try {
            String payloadJson = objectMapper.writeValueAsString(userSpecificEvent);
            return OutboxEvent.builder()
                    .id(UUID.fromString(userSpecificEvent.getEventId()))
                    .aggregateType(userSpecificEvent.getClass().getSimpleName())
                    .aggregateId(userSpecificEvent.getUserId())
                    .eventType(userSpecificEvent.getEventType())
                    .topic(topicName)
                    .payload(payloadJson)
                    .createdAt(userSpecificEvent.getTimestamp())
                    .build();
        } catch (JsonProcessingException e) {
            log.error("Could not serialize payload for user {}. Event will be skipped.", userId, e);
            return null;
        }
    }
}