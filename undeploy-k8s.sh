#!/bin/sh

# Apply the Kubernetes manifests
echo ">>> Un Deploying application to Kubernetes..."
kubectl delete -k k8s/overlays/development
