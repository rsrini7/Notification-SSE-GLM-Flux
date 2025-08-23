#!/bin/bash
set -e

echo "--> Waiting for Geode locator and server to be ready..."
# Loop until the server member is visible from the locator
until gfsh -e "connect --locator=localhost[10334]" -e "list members" | grep -q "$HOSTNAME"; do
  echo "--> Server not available yet. Retrying in 5 seconds..."
  sleep 5
done
echo "--> Cluster is ready."

echo "--> Creating regions..."
gfsh -e "connect --locator=localhost[10334]" \
     -e "create region --name=user-connections --type=REPLICATE" \
     -e "create region --name=connection-metadata --type=REPLICATE" \
     -e "create region --name=cluster-pod-connections --type=REPLICATE" \
     -e "create region --name=cluster-pod-heartbeats --type=REPLICATE" \
     -e "create region --name=pending-events --type=REPLICATE" \
     -e "create region --name=broadcast-content --type=REPLICATE" \
     -e "create region --name=active-group-broadcasts --type=REPLICATE" \
     -e "create region --name=user-messages --type=REPLICATE" \
     -e "create region --name=sse-messages --type=REPLICATE"

echo "--> All regions created successfully!"