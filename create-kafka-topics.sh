#!/bin/bash
# file: create-kafka-topics.sh

# Use environment variables for all settings, with sane defaults for local dev.
KAFKA_BROKERS="${KAFKA_BROKER_ADDRESS:-kafka-dev:29092}"
CLUSTER_NAME="${CLUSTER_NAME:-local}"
POD_NAME_PREFIX="${POD_NAME_PREFIX:-docker-pod-}"
MAX_REPLICAS="${MAX_REPLICAS:-2}"

# --- Wait for Kafka to be ready ---
echo "Waiting for Kafka broker at $KAFKA_BROKERS to be ready..."
until kafka-topics --bootstrap-server $KAFKA_BROKERS --list
do
  echo "Kafka not ready, sleeping for 2 seconds..."
  sleep 2
done
echo "Kafka is ready!"

# --- Create Singleton Topics ---
ORCHESTRATION_TOPIC="broadcast-orchestration"
ORCHESTRATION_DLT="${ORCHESTRATION_TOPIC}-dlt"
WORKER_DLT="broadcast-events-dlt"

echo "--- Creating Singleton Topics ---"
kafka-topics --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $ORCHESTRATION_TOPIC --partitions 1 --replication-factor 1
kafka-topics --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $ORCHESTRATION_DLT --partitions 1 --replication-factor 1
kafka-topics --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $WORKER_DLT --partitions 1 --replication-factor 1

# --- Create Pod-Specific Worker Topics ---
echo "--- Creating Worker Topics for Cluster: $CLUSTER_NAME ---"
WORKER_TOPIC_PREFIX="broadcast-events-"
for ((i=0; i<$MAX_REPLICAS; i++)); do
  # MODIFIED: Topic name is now built from variables to match the app logic perfectly.
  TOPIC_NAME="${CLUSTER_NAME}-${WORKER_TOPIC_PREFIX}${POD_NAME_PREFIX}${i}"
  echo "Creating topic: $TOPIC_NAME"
  kafka-topics --bootstrap-server $KAFKA_BROKERS --create --if-not-exists --topic $TOPIC_NAME --partitions 1 --replication-factor 1
done

echo "--- Topic creation script finished successfully. ---"