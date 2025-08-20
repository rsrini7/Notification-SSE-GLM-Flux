#!/bin/bash

mkdir -p /data/$HOSTNAME

# MODIFIED: Connect to the service name 'geode-locator'
gfsh start server --name=$HOSTNAME --locators=geode-locator[10334] --dir=/data/$HOSTNAME/ --hostname-for-clients=localhost "$@"

while true;
  do
    sleep 10
  done