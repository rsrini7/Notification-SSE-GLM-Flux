package com.example.broadcast.shared.exception;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import lombok.Getter;

/**
 * Custom exception to hold the failed message event along with the cause.
 * This ensures that business context (like userId and broadcastId) is not lost
 * when an error is propagated to the DLT handler.
 */
@Getter
public class MessageProcessingException extends RuntimeException {

    private final MessageDeliveryEvent failedEvent;

    public MessageProcessingException(String message, Throwable cause, MessageDeliveryEvent failedEvent) {
        super(message, cause);
        this.failedEvent = failedEvent;
    }
}