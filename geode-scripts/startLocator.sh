#!/bin/bash
set -e

echo "--> Starting Geode Locator..."
# The 'start locator' command runs and exits, but the gfsh shell stays open.
# We add a background sleep loop to keep the container from exiting.
gfsh <<EOF
start locator --name=locator --hostname-for-clients=localhost
EOF

echo "--> Locator process started. Running init script..."
/geode-scripts/geode-init.sh

echo "--> Initialization complete. Locator is running."
# This infinite loop keeps the container alive
while true; do sleep 10; done