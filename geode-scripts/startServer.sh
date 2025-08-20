#!/bin/bash

mkdir -p /data/$HOSTNAME

# ADDED --cache-xml-file to load region definitions
gfsh start server --name=$HOSTNAME --locators=locator[10334] --dir=/data/$HOSTNAME/ --hostname-for-clients=localhost --cache-xml-file=/geode-scripts/cache.xml "$@"

while true;
  do
    sleep 10
  done