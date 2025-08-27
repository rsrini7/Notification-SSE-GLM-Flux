#!/bin/bash
set -e

echo "--> Starting Geode Locator..."
gfsh <<EOF
start locator --name=locator --hostname-for-clients=localhost
EOF

echo "--> Locator is running and server will load regions from cache.xml."
# This infinite loop keeps the container alive
while true; do sleep 10; done