# Stage 1: Build the application using Maven
FROM maven:3.9-eclipse-temurin-21 AS builder

# Set the working directory inside the container
WORKDIR /app

# Copy the pom.xml file first to leverage Docker's layer caching
# This ensures dependencies are only re-downloaded if pom.xml changes
COPY pom.xml .

# Copy the rest of the source code
COPY src ./src

# Build the application, skipping tests to speed up the image build process.
# The result will be a JAR file in the /app/target/ directory.
RUN mvn package -DskipTests

# ---

# Stage 2: Create the final, lightweight runtime image
FROM eclipse-temurin:21-jre-alpine

# Set the working directory for the runtime image
WORKDIR /app

# Copy the executable JAR file from the 'builder' stage
COPY --from=builder /app/target/*.jar app.jar

# Expose the port the application will run on (this is for documentation;
# the actual port mapping is done in docker-compose.yml or Kubernetes)
EXPOSE 8081

# The command to run the application when the container starts
ENTRYPOINT ["java", "-jar", "app.jar"]