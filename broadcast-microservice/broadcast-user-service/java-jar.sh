rm logs\*
mvn clean package

echo Set environment variables for this session
export POD_NAME=docker-pod-0
export CLUSTER_NAME=local

echo Run the application
# The fix is to pass pod.name and cluster.name as -D system properties
java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg" "-Dpod.name=${POD_NAME}" "-Dcluster.name=${CLUSTER_NAME}" -jar target/broadcast-user-service-1.0.0.jar