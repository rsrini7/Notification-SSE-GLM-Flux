#!/bin/sh

# Apply the Kubernetes manifests
echo ">>> Deploying application to Kubernetes..."
kubectl apply -k k8s/overlays/development

# Pause for 10 seconds to allow pods to start up
echo "\n>>> Pausing for 5 seconds to allow pods to initialize..."
sleep 5

# Check the status of the pods
echo "\n>>> Checking pod status..."
gtimeout 10s kubectl get pods -n broadcast-system --watch

# Pause for 10 seconds to allow pods to start up
echo "\n>>> Continue to watch logs ENTER ?"
read


# Stream the logs from the deployment
echo "\n>>> Streaming application logs..."
kubectl logs -f deployment/broadcast-microservice -n broadcast-system