#!/bin/bash
set -e

echo "--> Starting Geode Locator for Kubernetes..."
gfsh <<EOF
start locator --name=locator --hostname-for-clients=geode-locator-service
EOF

echo "--> Locator process started. Running init script..."
/geode-scripts/geode-init.sh

echo "--> Initialization complete. Locator is running."
# This infinite loop keeps the container alive
while true; do sleep 10; done