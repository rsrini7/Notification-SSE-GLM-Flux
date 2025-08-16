del logs\*
mvn clean package

rem Set environment variables for this session
set "POD_NAME=docker-pod-0"
set "CLUSTER_NAME=local"

rem Run the application
java "-Duser.timezone=Asia/Kolkata" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-user-service-1.0.0.jar