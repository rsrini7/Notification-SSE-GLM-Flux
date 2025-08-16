#!/bin/bash
mvn clean package && java "-Duser.timezone=Asia/Kolkata" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-admin-service-1.0.0.jar