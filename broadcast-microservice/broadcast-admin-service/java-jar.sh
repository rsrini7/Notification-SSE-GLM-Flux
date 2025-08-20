#!/bin/bash
rm logs/*
mvn clean package

# Run the application
java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg,admin-only" -jar target/broadcast-admin-service-1.0.0.jar