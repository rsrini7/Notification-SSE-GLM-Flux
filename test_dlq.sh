#!/bin/bash

# A script to send a message with a guaranteed business logic error
# (an invalid eventType) to reliably test the DLQ mechanism.

# --- Configuration ---
TOPIC_NAME="broadcast-events"
USER_KEY="user-001"
# This payload is valid JSON, but the "eventType" will be rejected by the service.
INVALID_PAYLOAD='{"eventId":"invalid-event-type-'"$(date +%s)"'","broadcastId":123,"userId":"'"$USER_KEY"'","eventType":"INVALID_EVENT_TYPE","message":"This message will fail processing."}'


# --- Script Logic ---
echo "🔍 Finding Kafka container..."
KAFKA_CONTAINER_ID=$(docker ps --filter "name=kafka" --format "{{.ID}}")

if [ -z "$KAFKA_CONTAINER_ID" ]; then
    echo "❌ Error: Kafka container not found. Please ensure Docker Compose is running."
    exit 1
fi

echo "✅ Found Kafka container with ID: $KAFKA_CONTAINER_ID"
echo "✉️  Sending message with invalid eventType for user '$USER_KEY' to topic '$TOP_NAME'..."
echo "   Payload: $INVALID_PAYLOAD"

# Pipe the key-value pair to the kafka-console-producer.
echo "$USER_KEY:$INVALID_PAYLOAD" | docker exec -i "$KAFKA_CONTAINER_ID" \
    kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic "$TOPIC_NAME" \
    --property "parse.key=true" \
    --property "key.separator=:"

echo "🚀 Message sent successfully!"
echo "👀 The message should now appear in the DLQ Management panel after the configured retries."