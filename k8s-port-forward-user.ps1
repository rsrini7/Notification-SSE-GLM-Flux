#!/bin/sh
# Forwards local port 8082 to the user service's port 8082
Write-Host ">>> Forwarding local port 8082 to broadcast-user-service..."
Write-Host ">>> Access the user service at https://localhost:8082"
kubectl port-forward svc/broadcast-user-service -n broadcast-system 8082:8082