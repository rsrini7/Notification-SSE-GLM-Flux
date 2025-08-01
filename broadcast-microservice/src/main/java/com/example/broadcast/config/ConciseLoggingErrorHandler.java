package com.example.broadcast.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
// import org.springframework.kafka.listener.ListenerExecutionFailedException;

// import java.util.List;

/**
 * A custom error handler that extends the default one to provide concise logging.
 * Instead of printing a full stack trace for every failed message during retries,
 * it logs a single, informative line, dramatically reducing log noise in high-volume failure scenarios.
 */
public class ConciseLoggingErrorHandler extends DefaultErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConciseLoggingErrorHandler.class);

    public ConciseLoggingErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer, BackOff backOff) {
        super(deadLetterPublishingRecoverer, backOff);
    }

    // --- START: DEFINITIVE AND FINAL FIX ---
    @Override
    public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, org.apache.kafka.clients.consumer.Consumer<?, ?> consumer, org.springframework.kafka.listener.MessageListenerContainer container) {
        // Log the concise, one-line error message.
        LOGGER.error(
            "Error processing record during retry. topic={}, partition={}, offset={}, exception_message='{}'",
            record.topic(),
            record.partition(),
            record.offset(),
            thrownException.getMessage()
        );

        // Optionally, log the full stack trace at DEBUG level.
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Full stack trace for failed record:", thrownException);
        }

        // Call the super method to ensure the retry and DLT logic still executes.
        return super.handleOne(thrownException, record, consumer, container);
    }

    // This method is called for batch listeners, but we can add the same concise logging here for completeness.
    @Override
    public void handleBatch(Exception thrownException, org.apache.kafka.clients.consumer.ConsumerRecords<?, ?> data, org.apache.kafka.clients.consumer.Consumer<?, ?> consumer, org.springframework.kafka.listener.MessageListenerContainer container, Runnable invokeListener) {
        LOGGER.error(
            "Error processing batch of records. first_record_offset={}, exception_message='{}'",
            data.iterator().hasNext() ? data.iterator().next().offset() : "N/A",
            thrownException.getMessage()
        );
        super.handleBatch(thrownException, data, consumer, container, invokeListener);
    }
    // --- END: DEFINITIVE AND FINAL FIX ---
}