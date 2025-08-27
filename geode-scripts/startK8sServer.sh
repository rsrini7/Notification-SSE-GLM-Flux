#!/bin/bash
set -e

echo "--> Starting Geode Server for Kubernetes with cache.xml..."
gfsh <<EOF
start server --name=$HOSTNAME --locators=geode-locator-service[10334] --dir=/data/$HOSTNAME/ --hostname-for-clients=geode-server-service --cache-xml-file=/geode-scripts/cache.xml --classpath=/geode-server/classpath/app.jar
EOF

echo "--> Server is running."
# This infinite loop keeps the container alive
while true; do sleep 10; done