#!/bin/sh
# MODIFIED: Use the service name 'geode-locator' for the name and remove the command from docker-compose
gfsh start locator --name=geode-locator --mcast-port=0

# Keep the process alive
while true;
do
  sleep 10
done