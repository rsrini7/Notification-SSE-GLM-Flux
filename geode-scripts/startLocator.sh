#!/bin/sh
# MODIFIED: Use the simple name 'locator'
gfsh start locator --name=locator --mcast-port=0

# Keep the process alive
while true;
do
  sleep 10
done