package com.example.broadcast.admin.event;

import com.example.broadcast.admin.service.BroadcastTargetingService;
import com.example.broadcast.shared.aspect.Monitored;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Slf4j
@RequiredArgsConstructor
public class BroadcastEventListener {

    private final BroadcastTargetingService broadcastTargetingService;

    @Monitored("event-listener")
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleBroadcastCreatedEvent(BroadcastCreatedEvent event) {
        log.info("Transaction committed for broadcast {}. Starting async pre-computation.", event.broadcastId());
        broadcastTargetingService.precomputeAndStoreTargetUsers(event.broadcastId());
    }
}