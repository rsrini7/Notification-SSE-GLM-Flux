#!/bin/bash
rm logs\*
mvn clean package && java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-admin-service-1.0.0.jar