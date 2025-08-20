#!/bin/bash

mkdir -p /data/$HOSTNAME

gfsh start server --name=$HOSTNAME --locators=locator[10334] --dir=/data/$HOSTNAME/ --hostname-for-clients=localhost  "$@"

while true;
  do
    sleep 10
  done