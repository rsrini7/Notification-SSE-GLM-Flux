#!/bin/bash
set -e

echo "--> Starting Geode Server with cache.xml configuration..."
gfsh <<EOF
start server --name=$HOSTNAME --locators=locator[10334] --dir=/data/$HOSTNAME/ --hostname-for-clients=localhost --cache-xml-file=/geode-scripts/cache.xml --classpath=/geode-server/classpath/app.jar
EOF

echo "--> Server is running."
# This infinite loop keeps the container alive
while true; do sleep 10; done