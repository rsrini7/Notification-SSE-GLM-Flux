#!/bin/sh

# Apply the Kubernetes manifests
echo ">>> Un Deploying application to Kubernetes..."
kubectl delete -k k8s/overlays/development

# Pause for 10 seconds to allow pods to start up
echo "\n>>> Pausing for 5 seconds to allow pods to delete..."
sleep 5

# Check the status of the pods
echo "\n>>> Checking pod status..."
gtimeout 10s kubectl get pods -n broadcast-system --watch
