package com.example.broadcast.shared.config;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;

/**
 * A custom error handler that provides concise logging for Kafka consumer errors.
 * Instead of printing a full stack trace for every failed message during retries,
 * it logs a single, structured line, dramatically reducing log noise.
 */
public class ConciseLoggingErrorHandler extends DefaultErrorHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConciseLoggingErrorHandler.class);

    public ConciseLoggingErrorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer, BackOff backOff) {
        super(deadLetterPublishingRecoverer, backOff);
    }

    @Override
    public boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, MessageListenerContainer container) {
        // This is the core of the fix. We override the default logging.
        log(record, thrownException);
        // Then, we call the super method to ensure the retry and DLT logic still executes.
        return super.handleOne(thrownException, record, consumer, container);
    }

    private void log(ConsumerRecord<?, ?> record, Exception exception) {
        LOGGER.error(
            "Error processing record. topic={}, partition={}, offset={}, exception_message='{}'",
            record.topic(),
            record.partition(),
            record.offset(),
            // We get the root cause message for clarity
            exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage()
        );

        // Optionally, log the full stack trace at DEBUG level for deep debugging.
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Full stack trace for failed record:", exception);
        }
    }
}