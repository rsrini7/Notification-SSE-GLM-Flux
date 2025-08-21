#!/bin/sh
# Forwards local port 8081 to the admin service's port 8081
Write-Host ">>> Forwarding local port 8081 to broadcast-admin-service..."
Write-Host ">>> Access the admin service at https://localhost:8081"
kubectl port-forward svc/broadcast-admin-service -n broadcast-system 8081:8081