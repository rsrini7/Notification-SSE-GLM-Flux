package com.example.broadcast.service;

import com.example.broadcast.dto.MessageDeliveryEvent;
import com.example.broadcast.repository.UserBroadcastRepository;
import com.example.broadcast.util.Constants.DeliveryStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DltFailureHandler {

    private final UserBroadcastRepository userBroadcastRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleProcessingFailure(MessageDeliveryEvent event) {
        if (event == null || event.getUserId() == null || event.getBroadcastId() == null) {
            log.error("Cannot handle processing failure: event or its key fields are null.");
            return;
        }

        userBroadcastRepository.findByUserIdAndBroadcastId(event.getUserId(), event.getBroadcastId())
            .ifPresent(userMessage -> {
                userBroadcastRepository.updateDeliveryStatus(userMessage.getId(), DeliveryStatus.FAILED.name());
                log.info("Marked UserBroadcastMessage (ID: {}) as FAILED for user {} due to processing error.", userMessage.getId(), event.getUserId());
            });
    }
}