#!/bin/bash
set -e

echo "--> Starting Geode Server..."
gfsh <<EOF
start server --name=$HOSTNAME --locators=locator[10334] --hostname-for-clients=localhost --J=-Dgemfire.subscription-conflation-enabled=true $@
EOF

echo "--> Server is running."
# This infinite loop keeps the container alive
while true; do sleep 10; done