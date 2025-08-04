#!/bin/bash

# A script to send a message with a business logic error (invalid broadcastId)
# for a specific user, to test redrive failures in the DLQ.

# --- Configuration ---
TOPIC_NAME="broadcast-events"
# The user key for the Kafka message. This ensures it's processed for this user.
USER_KEY="user-001"
# A valid JSON payload, but with a broadcastId that doesn't exist.
INVALID_PAYLOAD='{"eventId":"bad-broadcast-id-'"$(date +%s)"'","broadcastId":99999,"userId":"'"$USER_KEY"'","eventType":"CREATED","message":"This message has a business logic error."}'


# --- Script Logic ---
echo "🔍 Finding Kafka container..."
KAFKA_CONTAINER_ID=$(docker ps --filter "name=kafka" --format "{{.ID}}")

if [ -z "$KAFKA_CONTAINER_ID" ]; then
    echo "❌ Error: Kafka container not found. Please ensure Docker Compose is running."
    exit 1
fi

echo "✅ Found Kafka container with ID: $KAFKA_CONTAINER_ID"
echo "✉️  Sending message for user '$USER_KEY' to topic '$TOPIC_NAME'..."
echo "   Payload: $INVALID_PAYLOAD"

# Pipe the key-value pair to the kafka-console-producer.
# We need to tell the producer to parse keys.
echo "$USER_KEY:$INVALID_PAYLOAD" | docker exec -i "$KAFKA_CONTAINER_ID" \
    kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic "$TOPIC_NAME" \
    --property "parse.key=true" \
    --property "key.separator=:"

echo "🚀 Message sent successfully!"
echo "👀 The message should now appear in the DLQ Management panel."
echo "   Try to 'Redrive' it from the UI; it will fail and reappear."