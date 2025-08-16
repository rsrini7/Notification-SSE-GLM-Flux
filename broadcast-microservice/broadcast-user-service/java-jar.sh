rm logs\*
mvn clean package

echo Set environment variables for this session
export POD_NAME=docker-pod-0
export CLUSTER_NAME=local

echo Run the application
java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-user-service-1.0.0.jar