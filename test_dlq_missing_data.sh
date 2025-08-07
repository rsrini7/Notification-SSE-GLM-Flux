#!/bin/bash
TOPIC_NAME="broadcast-events-selected"
USER_KEY="user-001"
INVALID_PAYLOAD='{"eventId":"missing-data-'"$(date +%s)"'","broadcastId":99999,"userId":"'"$USER_KEY"'","eventType":"CREATED","message":"This message references data that does not exist yet."}'

echo "🔍 Finding Kafka container..."
KAFKA_CONTAINER_ID=$(docker ps --filter "name=kafka" --format "{{.ID}}")
if [ -z "$KAFKA_CONTAINER_ID" ]; then
    echo "❌ Kafka container not found." && exit 1
fi
echo "✅ Found Kafka container: $KAFKA_CONTAINER_ID"
echo "✉️  Sending message with missing data reference to topic '$TOPIC_NAME'..."
echo "$USER_KEY:$INVALID_PAYLOAD" | docker exec -i "$KAFKA_CONTAINER_ID" kafka-console-producer --bootstrap-server localhost:9092 --topic "$TOPIC_NAME" --property "parse.key=true" --property "key.separator=:"
echo "🚀 Message sent. It will fail processing and land in the DLT."
