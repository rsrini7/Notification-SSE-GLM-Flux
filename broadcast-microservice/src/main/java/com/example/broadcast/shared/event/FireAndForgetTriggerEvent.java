package com.example.broadcast.shared.event;

import org.springframework.context.ApplicationEvent;

/**
 * An event published when a "Fire-and-Forget" message is delivered,
 * triggering its immediate expiration in a separate transaction.
 */
public class FireAndForgetTriggerEvent extends ApplicationEvent {
    
    private final Long broadcastId;

    public FireAndForgetTriggerEvent(Object source, Long broadcastId) {
        super(source);
        this.broadcastId = broadcastId;
    }

    public Long getBroadcastId() {
        return broadcastId;
    }
}