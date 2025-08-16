del logs\*
mvn clean package && java "-Duser.timezone=Asia/Kolkata" "-DPOD_NAME=admin-local" "-DCLUSTER_NAME=local" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-admin-service-1.0.0.jar