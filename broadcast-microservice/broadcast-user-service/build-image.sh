mvn clean package
echo mvn spring-boot:build-image -Dspring-boot.build-image.imageName=broadcast-user-service:1.0.0
mvn spring-boot:build-image "-Dspring-boot.build-image.imageName=broadcast-user-service:1.0.0"