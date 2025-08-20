#!/bin/bash
set -e

# Wait for server to be ready
sleep 5

# Connect to locator and create regions
gfsh -e "connect --locator=locator[10334]" \
     -e "create region --name=user-connections --type=REPLICATE" \
     -e "create region --name=connection-to-user --type=REPLICATE" \
     -e "create region --name=online-users --type=REPLICATE" \
     -e "create region --name=pod-connections --type=REPLICATE" \
     -e "create region --name=heartbeat --type=REPLICATE" \
     -e "create region --name=pending-events --type=REPLICATE" \
     -e "create region --name=broadcast-content --type=REPLICATE" \
     -e "create region --name=active-group-broadcasts --type=REPLICATE" \
     -e "create region --name=user-messages --type=REPLICATE" \
     -e "create region --name=sse-messages --type=REPLICATE" \
     -e "create region --name=dlt-armed --type=REPLICATE" \
     -e "create region --name=dlt-failure-ids --type=REPLICATE"

echo "All regions created successfully!"