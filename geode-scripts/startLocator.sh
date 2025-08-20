#!/bin/bash
set -e

echo "--> Starting Geode Locator in background..."
# Start the locator process in the background
gfsh start locator --name=locator --hostname-for-clients=localhost &

# Capture the Process ID of the locator
LOCATOR_PID=$!
echo "--> Locator started with PID: $LOCATOR_PID"

# Run the initialization script to create regions
/geode-scripts/geode-init.sh

# Wait for the locator process to exit, which keeps the container running
echo "--> Initialization complete. Tailing locator logs..."
wait $LOCATOR_PID