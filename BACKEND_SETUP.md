# Broadcast Microservice Setup Guide

This guide provides step-by-step instructions to set up the broadcast microservice project on your local machine.

## Prerequisites

Before you begin, ensure you have the following installed:

1. **Java 17+** - Required for Spring Boot 3.x
2. **Maven 3.8+** - For building the project
3. **Docker** - For running h2 database and Kafka (optional but recommended)
4. **Git** - For version control
5. **IDE** - IntelliJ IDEA or VS Code with Java extensions

## Step 1: Clone the Project

```bash
# Clone the repository
git clone <your-repository-url>
cd broadcast-microservice

# Or if you have the project files, navigate to the project directory
cd /path/to/your/project/broadcast-microservice
```

## Step 2: Install Java and Maven

### For macOS (using Homebrew):
```bash
# Install Java 17
brew install openjdk@17

# Install Maven
brew install maven

# Set Java 17 as default
echo 'export JAVA_HOME=/usr/local/opt/openjdk@17' >> ~/.zshrc
source ~/.zshrc
```

### For Ubuntu/Debian:
```bash
# Install Java 17
sudo apt update
sudo apt install openjdk-17-jdk maven

# Set Java 17 as default
sudo update-alternatives --config java
```

### For Windows:
1. Download Java 17 from [Oracle](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
2. Download Maven from [Apache Maven](https://maven.apache.org/download.cgi)
3. Set environment variables:
   - `JAVA_HOME`: Path to Java 17 installation
   - `MAVEN_HOME`: Path to Maven installation
   - Add `%JAVA_HOME%\bin` and `%MAVEN_HOME%\bin` to PATH

## Step 3: Verify Installation

```bash
# Check Java version
java -version
# Expected output: openjdk version "17.x.x"

# Check Maven version
mvn -version
# Expected output: Apache Maven 3.x.x
```

## Step 4: Set Up Database (h2)

The project uses h2 database. You can either:

### Option A: Use Embedded h2 (Easiest)
The project is configured to use embedded h2, so no additional setup is needed.

### Option B: Use Docker for h2
```bash
# Create a docker-compose.yml file
cat > docker-compose.yml << EOF
version: '3.8'
services:
  h2:
    image: oscarfonts/h2
    ports:
      - "1521:1521"
      - "81:81"
    environment:
      - H2_OPTIONS=-ifNotExists
    volumes:
      - h2_data:/opt/h2-data

volumes:
  h2_data:
EOF

# Start h2
docker-compose up -d

# Access h2 web console at http://localhost:81
# JDBC URL: jdbc:h2:tcp://localhost:1521/test
```

## Step 5: Set Up Kafka (Optional but Recommended)

### Using Docker:
```bash
# Add Kafka to docker-compose.yml
cat >> docker-compose.yml << 'EOF'

  kafka:
    image: confluentinc/cp-kafka:7.3.0
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: true

  zookeeper:
    image: confluentinc/cp-zookeeper:7.3.0
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
EOF

# Start Kafka
docker-compose up -d
```

## Step 6: Configure Application Properties

The project uses `application.yml` for configuration. Check the existing configuration:

```bash
# View the current configuration
cat src/main/resources/application.yml
```

If you need to modify the configuration, edit `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: broadcast-microservice
  
  # h2 Database Configuration
  datasource:
    url: jdbc:h2:mem:broadcastdb
    driver-class-name: org.h2.Driver
    username: sa
    password: password
  
  # JPA/Hibernate Configuration
  jpa:
    hibernate:
      ddl-auto: create-drop
    show-sql: true
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
        format_sql: true
  
  # Kafka Configuration
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: broadcast-service-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.example.broadcast.dto
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

# Server Configuration
server:
  port: 8080

# Application-specific Configuration
broadcast:
  kafka:
    topic:
      name: broadcast-events
  sse:
    timeout: 300000
    heartbeat-interval: 30000
  pod:
    id: local-pod

# Logging Configuration
logging:
  level:
    com.example.broadcast: DEBUG
    org.springframework.kafka: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss} - %msg%n"
```

## Step 7: Build the Project

```bash
# Clean and compile the project
mvn clean compile

# Run tests (if any)
mvn test

# Package the application
mvn package -DskipTests
```

## Step 8: Run the Application

### Option A: Using Maven
```bash
# Run the application
mvn spring-boot:run
```

### Option B: Using Java JAR
```bash
# Run the packaged JAR
java -jar target/broadcast-microservice-1.0.0.jar
```

### Option C: Using Docker
```bash
# Create Dockerfile
cat > Dockerfile << 'EOF'
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/broadcast-microservice-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
EOF

# Build Docker image
docker build -t broadcast-microservice .

# Run Docker container
docker run -p 8080:8080 broadcast-microservice
```

## Step 9: Verify the Application is Running

```bash
# Check if the application is running
curl http://localhost:8080/actuator/health

# Expected response:
{
  "status": "UP"
}
```

## Step 10: Test the API Endpoints

### 1. Create a Broadcast
```bash
curl -X POST http://localhost:8080/broadcasts \
  -H "Content-Type: application/json" \
  -d '{
    "senderId": "admin-001",
    "senderName": "System Administrator",
    "content": "Welcome to the broadcast system!",
    "targetType": "ALL",
    "priority": "NORMAL",
    "category": "GENERAL"
  }'
```

### 2. Get All Active Broadcasts
```bash
curl http://localhost:8080/broadcasts
```

### 3. Get Broadcast by ID
```bash
curl http://localhost:8080/broadcasts/1
```

### 4. Test SSE Connection
```bash
# Test SSE connection (this will stream events)
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/sse/connect?userId=user-001"
```

### 5. Get SSE Statistics
```bash
curl http://localhost:8080/sse/stats
```

## Step 11: Set Up Monitoring (Optional)

### Access Actuator Endpoints
```bash
# Health check
curl http://localhost:8080/actuator/health

# Metrics
curl http://localhost:8080/actuator/metrics

# Info
curl http://localhost:8080/actuator/info
```

### Enable All Actuator Endpoints
Add to `application.yml`:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: "*"
  endpoint:
    health:
      show-details: always
```

## Step 12: Set Up IDE Development

### For IntelliJ IDEA:
1. Open the project in IntelliJ
2. Wait for Maven dependencies to download
3. Go to `BroadcastMicroserviceApplication.java`
4. Right-click and select "Run 'BroadcastMicroserviceApplication'"

### For VS Code:
1. Install the "Extension Pack for Java"
2. Open the project folder
3. VS Code will detect the Maven project and import dependencies
4. Press F5 to debug or Ctrl+F5 to run

## Step 13: Common Issues and Solutions

### Issue 1: Port Already in Use
```bash
# Kill process using port 8080
lsof -ti:8080 | xargs kill -9
```

### Issue 2: Maven Dependencies Not Found
```bash
# Clean Maven local repository
mvn clean install -U
```

### Issue 3: Database Connection Issues
```bash
# Reset h2 database
docker-compose down -v
docker-compose up -d
```

### Issue 4: Kafka Connection Issues
```bash
# Reset Kafka
docker-compose down -v
docker-compose up -d
```

## Step 14: Development Workflow

```bash
# 1. Pull latest changes
git pull origin main

# 2. Create a feature branch
git checkout -b feature/your-feature-name

# 3. Make changes and test
mvn clean compile
mvn test

# 4. Commit changes
git add .
git commit -m "Your commit message"

# 5. Push changes
git push origin feature/your-feature-name
```

## Step 15: Production Deployment

### Build for Production
```bash
# Build with production profile
mvn clean package -Pprod

# Run with production configuration
java -jar -Dspring.profiles.active=prod target/broadcast-microservice-1.0.0.jar
```

### Environment Variables
```bash
# Set environment variables
export SPRING_PROFILES_ACTIVE=prod
export BROADCAST_POD_ID=prod-pod-001
export KAFKA_BOOTSTRAP_SERVERS=kafka:9092

# Run with environment variables
java -jar target/broadcast-microservice-1.0.0.jar
```

## Architecture Overview

### Components
- **Spring Boot 3.2.0** - Main framework
- **Spring WebFlux** - Reactive web framework
- **h2 Database** - In-memory database
- **Kafka** - Message streaming platform
- **Caffeine** - High-performance caching
- **Server-Sent Events (SSE)** - Real-time communication

### Key Features
- **High Scalability**: Supports 40,000+ users and 30,000+ concurrent connections
- **Real-time Messaging**: SSE for instant message delivery
- **Event-Driven Architecture**: Kafka for reliable message processing
- **Monitoring**: Actuator endpoints and distributed tracing
- **Caching**: Caffeine for high-performance operations

### API Endpoints

#### Broadcast Management
- `POST /broadcasts` - Create a new broadcast
- `GET /broadcasts` - Get all active broadcasts
- `GET /broadcasts/{id}` - Get broadcast by ID
- `DELETE /broadcasts/{id}` - Cancel a broadcast
- `GET /broadcasts/{id}/stats` - Get broadcast statistics

#### SSE Connections
- `GET /sse/connect` - Establish SSE connection
- `POST /sse/heartbeat` - Keep connection alive
- `POST /sse/disconnect` - Close connection
- `GET /sse/stats` - Get connection statistics
- `GET /sse/connected/{userId}` - Check if user is connected

#### Health & Monitoring
- `GET /actuator/health` - Health check
- `GET /actuator/metrics` - Application metrics
- `GET /actuator/info` - Application information

## Next Steps

1. **Explore the API**: Use Postman or curl to test all endpoints
2. **Monitor the Application**: Check Actuator endpoints and logs
3. **Scale the Application**: Run multiple instances for testing
4. **Integrate with Frontend**: Connect your Next.js frontend to the backend
5. **Add Tests**: Write unit and integration tests
6. **Set Up CI/CD**: Configure GitHub Actions or Jenkins for automated deployment

## Troubleshooting

### Common Compilation Issues
If you encounter compilation errors similar to the ones that were fixed:

1. **Jakarta EE Migration**: Ensure all `javax.*` imports are replaced with `jakarta.*`
2. **Lombok Annotations**: Verify all model classes have `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
3. **Reactive SSE**: Make sure SSE implementation uses `Flux<String>` and `Sinks.Many<String>`

### Performance Tuning
- Adjust thread pool sizes in `application.yml`
- Configure Kafka producer/consumer settings for your workload
- Tune Caffeine cache settings based on your data patterns

### Security Considerations
- Add authentication and authorization
- Configure HTTPS for production
- Validate all input parameters
- Implement rate limiting

---

The broadcast microservice is now set up and ready for development and testing!

## Support

For issues and questions:
1. Check the troubleshooting section above
2. Review the application logs
3. Check Actuator endpoints for health status
4. Consult the Spring Boot and Kafka documentation

## License

This project is licensed under the MIT License.