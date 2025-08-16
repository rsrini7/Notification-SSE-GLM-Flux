del logs\*
mvn clean package && java "-Duser.timezone=Asia/Kolkata" "-DPOD_NAME=docker-pod-0" "-DCLUSTER_NAME=local" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-user-service-1.0.0.jar
