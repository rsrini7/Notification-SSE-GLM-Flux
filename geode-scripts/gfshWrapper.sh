#!/bin/sh
# ADDED --hostname-for-clients to advertise a reachable address to external clients
"$@" --hostname-for-clients=localhost

while true;
do
  sleep 10
done