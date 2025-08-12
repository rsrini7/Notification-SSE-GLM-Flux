#!/bin/sh

# Check if an argument is provided
if [ -z "$1" ]; then
    echo "Usage: $0 <service>"
    echo "  service: 'admin' or 'user'"
    exit 1
fi

# Determine the service name based on the argument
SERVICE_NAME=""
if [ "$1" = "admin" ]; then
    SERVICE_NAME="broadcast-admin-service"
elif [ "$1" = "user" ]; then
    SERVICE_NAME="broadcast-user-service"
else
    echo "Error: Invalid service specified. Use 'admin' or 'user'."
    exit 1
fi

# Stream the logs from the specified deployment
echo ">>> Streaming logs for deployment/$SERVICE_NAME..."
kubectl logs -f deployment/$SERVICE_NAME -n broadcast-system