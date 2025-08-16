#!/bin/bash
# file: create-kafka-topics.sh

# MODIFIED: Use an environment variable for the broker address,
# with a default value for the production profile.
KAFKA_BROKERS="${KAFKA_BROKER_ADDRESS:-kafka-prod:9092}"
MAX_REPLICAS=10

# --- Wait for Kafka to be ready ---
echo "Waiting for Kafka broker at $KAFKA_BROKERS to be ready..."
until kafka-topics.sh --bootstrap-server $KAFKA_BROKERS --list > /dev/null 2>&1
do
  echo "Kafka not ready, sleeping for 5 seconds..."
  sleep 5
done
echo "Kafka is ready!"

# --- Create Singleton Topics ---
ORCHESTRATION_TOPIC="broadcast-orchestration"
ORCHESTRATION_DLT="${ORCHESTRATION_TOPIC}-dlt"
WORKER_DLT="broadcast-events-dlt"

echo "--- Creating Singleton Topics ---"
kafka-topics.sh --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $ORCHESTRATION_TOPIC --partitions 1 --replication-factor 1
kafka-topics.sh --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $ORCHESTRATION_DLT --partitions 1 --replication-factor 1
kafka-topics.sh --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $WORKER_DLT --partitions 1 --replication-factor 1

# --- Create Pod-Specific Worker Topics ---
echo ""
echo "--- Creating Worker Topics ---"
WORKER_TOPIC_PREFIX="broadcast-events-"
for ((i=0; i<$MAX_REPLICAS; i++)); do
  TOPIC_NAME="${WORKER_TOPIC_PREFIX}docker-pod-${i}"
  echo "Creating topic: $TOPIC_NAME"
  kafka-topics.sh --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $TOPIC_NAME --partitions 1 --replication-factor 1
done

echo "--- Topic creation script finished successfully. ---"