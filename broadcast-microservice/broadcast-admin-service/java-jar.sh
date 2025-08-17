#!/bin/bash
rm logs/*
mvn clean package

# Set environment variables for this session.
# export POD_NAME="admin-local-0"
# export CLUSTER_NAME="cluster-a"

# Run the application
java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg,admin-only" -jar target/broadcast-admin-service-1.0.0.jar