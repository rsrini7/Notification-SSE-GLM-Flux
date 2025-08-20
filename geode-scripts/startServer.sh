#!/bin/bash
set -e

echo "--> Starting Geode Server..."
gfsh start server \
    --name=$HOSTNAME \
    --locators=locator[10334] \
    --dir=/data/$HOSTNAME/ \
    --hostname-for-clients=localhost "$@"

# Keep the process alive
wait