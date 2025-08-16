del logs\*
mvn clean package && java -jar "-Duser.timezone=Asia/Kolkata -Dspring.profiles.active=dev-pg" target/broadcast-user-service-1.0.0.jar
