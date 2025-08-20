#!/bin/bash
set -e

# Loop until the connection to the locator is successful
echo "--> Waiting for Geode locator to be ready..."
until gfsh -e "connect --locator=locator[10334]" -e "list members"; do
  echo "--> Locator not available yet. Retrying in 5 seconds..."
  sleep 5
done
echo "--> Connected to locator successfully."

# Now that we're connected, create the regions
echo "--> Creating regions..."
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

echo "--> All regions created successfully!"