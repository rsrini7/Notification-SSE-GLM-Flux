# Broadcast Messaging Microservice

A high-scale broadcast messaging microservice built with Spring Boot WebFlux, designed to handle 400,000+ registered users with 30,000+ concurrent SSE connections.

## Features

### Core Functionality
- **Real-time SSE Delivery**: Sub-second latency for online users
- **Persistent Storage**: h2 Database with admin and user-side tracking
- **Event Streaming**: Kafka-based fan-out with at-least-once semantics
- **High-Performance Caching**: Caffeine for low-latency operations
- **Scalable Architecture**: Kubernetes-ready with HPA and PDB

### Technical Capabilities
- **Reactive Programming**: Spring WebFlux with non-blocking I/O
- **Database Optimization**: Batch operations, proper indexing, connection pooling
- **Message Delivery**: Guaranteed delivery with retry mechanisms
- **Monitoring & Observability**: Comprehensive metrics, logging, and tracing
- **High Availability**: Multi-replica deployment with failover

## Architecture

### System Overview
```
┌─────────────┐    ┌─────────────────┐    ┌─────────────┐
│   Admin UI  │───▶│ Broadcast API   │───▶│   h2 DB    │
└─────────────┘    │ (Spring Boot)   │    └─────────────┘
                   └─────────────────┘
                           │
                           ▼
                   ┌─────────────────┐    ┌─────────────┐
                   │     Kafka       │───▶│  Caffeine   │
                   │   Event Stream  │    │   Cache     │
                   └─────────────────┘    └─────────────┘
                           │
                           ▼
                   ┌─────────────────┐    ┌─────────────┐
                   │   SSE Endpoint  │◀───│  React UI   │
                   │ (WebFlux)       │    └─────────────┘
                   └─────────────────┘
```

### Data Flow
1. **Admin creates broadcast** → Stored in h2 DB → Kafka event published
2. **Kafka consumer** processes event → Updates cache → Delivers via SSE
3. **User receives message** → Marks as read → Status updated in DB
4. **Offline users** → Messages cached → Delivered on reconnect

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.6+
- Docker & Kubernetes (for deployment)
- Kafka 3.7.1+ (Confluence 7.7.1)

### Build and Run
```bash
# Clone the repository
git clone <repository-url>
cd broadcast-microservice

# Build the application
mvn clean package

# Run locally
java -jar target/broadcast-microservice-1.0.0.jar

# Or with Maven
mvn spring-boot:run
```

### Docker Build
```bash
# Build Docker image
docker build -t broadcast-microservice:1.0.0 .

# Run container
docker run -p 8081:8081 broadcast-microservice:1.0.0
```

## Configuration

### Application Configuration
Key configuration options in `application.yml`:

```yaml
broadcast:
  sse:
    timeout: 300000          # 5 minutes
    heartbeat-interval: 30000 # 30 seconds
  
  cache:
    user-connections:
      maximum-size: 50000
      expire-after-write: 1h
    user-messages:
      maximum-size: 100000
      expire-after-write: 24h
  
  kafka:
    topic:
      name: broadcast-events
      partitions: 10
      replication-factor: 3
```

### Environment Variables
```bash
export BROADCAST_POD_ID=pod-001
export JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC"
export SPRING_PROFILES_ACTIVE=prod
```

## API Documentation

### Admin API

#### Create Broadcast
```http
POST /api/broadcasts
Content-Type: application/json

{
  "senderId": "admin-001",
  "senderName": "System Administrator",
  "content": "Important announcement",
  "targetType": "ALL",
  "priority": "HIGH",
  "category": "SYSTEM"
}
```

#### Get Broadcasts
```http
GET /api/broadcasts
```

#### Get Broadcast Stats
```http
GET /api/broadcasts/{id}/stats
```

#### Cancel Broadcast
```http
DELETE /api/broadcasts/{id}
```

### SSE API

#### Connect to SSE
```http
GET /api/sse/connect?userId=user-001&sessionId=session-123
```

#### Send Heartbeat
```http
POST /api/sse/heartbeat?userId=user-001&sessionId=session-123
```

#### Mark Message as Read
```http
POST /api/sse/read?userId=user-001&messageId=123
```

#### Get Connection Stats
```http
GET /api/sse/stats
```

## Database Schema

### Core Tables
- **broadcast_messages**: Admin-side broadcast records
- **user_broadcast_messages**: User-specific delivery tracking
- **user_sessions**: Connection and session management
- **user_preferences**: Notification preferences
- **broadcast_statistics**: Performance metrics

### Key Features
- **Optimized Indexing**: Fast queries for large datasets
- **Batch Operations**: High-performance bulk inserts/updates
- **Relationship Management**: Proper foreign key constraints
- **Data Integrity**: Validation and constraints

## Scaling Guide

### Horizontal Scaling
- **Pod Count**: Start with 3, scale to 20+ based on load
- **Resource Allocation**: 3-5GB memory, 1-2 CPU cores per pod
- **Load Balancing**: Kubernetes Service with session affinity

### Vertical Scaling
- **Memory**: Adjust based on cache size requirements
- **CPU**: Scale based on message processing load
- **Network**: Ensure sufficient bandwidth for SSE traffic

### Kafka Scaling
- **Partitions**: 10 partitions for parallel processing
- **Replication**: 3 replicas for fault tolerance
- **Consumer Groups**: Multiple consumers for load distribution

## Monitoring

### Metrics
- **Application Metrics**: Spring Boot Actuator endpoints
- **Custom Metrics**: SSE connections, message delivery rates
- **Infrastructure**: CPU, memory, network, disk I/O

### Health Checks
```bash
# Application health
curl http://localhost:8081/api/actuator/health

# Detailed health
curl http://localhost:8081/api/actuator/health/broadcast

# Metrics
curl http://localhost:8081/api/actuator/prometheus
```

### Logging
- **Structured Logging**: JSON format for easy parsing
- **Log Levels**: Configurable log levels for different components
- **Centralized Logging**: Integration with ELK stack or similar

### Alerting
- **Critical Alerts**: Service down, database errors
- **Warning Alerts**: High memory/CPU usage, connection limits
- **Performance Alerts**: High latency, delivery failures

## Deployment

### Kubernetes Deployment
```bash
# Apply all configurations
kubectl apply -f k8s/

# Verify deployment
kubectl get pods -n broadcast-system
kubectl get hpa -n broadcast-system
kubectl get svc -n broadcast-system
```

### Key Kubernetes Resources
- **Deployment**: Manages pod lifecycle
- **Service**: Network endpoints
- **HPA**: Horizontal autoscaling
- **PDB**: Pod disruption budget
- **Network Policy**: Traffic control

### High Availability
- **Multi-replica**: Minimum 3 replicas
- **Anti-affinity**: Spread pods across nodes
- **Rolling Updates**: Zero-downtime deployments
- **Health Checks**: Liveness and readiness probes

## Performance Optimization

### JVM Tuning
```bash
JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
```

### Database Optimization
- **Connection Pool**: 50 connections per pod
- **Batch Size**: 1000 records for bulk operations
- **Query Optimization**: Proper indexing and query plans

### Cache Optimization
- **Cache Size**: Tune based on memory availability
- **Expiration**: Balance freshness vs performance
- **Eviction**: LRU eviction for hot data

### Kafka Optimization
- **Batch Size**: Optimize for throughput vs latency
- **Compression**: Use LZ4 for better performance
- **Retries**: Configure for resilience

## Security

### Application Security
- **Input Validation**: Validate all user inputs
- **SQL Injection**: Use parameterized queries
- **XSS Protection**: Sanitize user-generated content

### Network Security
- **TLS**: Encrypt all external communications
- **Network Policies**: Restrict traffic to required services
- **Authentication**: Service account-based auth

### Data Security
- **Encryption**: Encrypt sensitive data at rest
- **Access Control**: RBAC for all resources
- **Audit Logging**: Log all administrative actions

## Troubleshooting

### Common Issues

#### High Memory Usage
```bash
# Check memory usage
kubectl top pods -n broadcast-system

# Check heap usage
kubectl exec -it <pod-name> -- jmap -heap <pid>
```

#### SSE Connection Issues
```bash
# Check connection count
curl http://localhost:8081/api/sse/stats

# Check pod logs
kubectl logs -f deployment/broadcast-microservice -n broadcast-system
```

#### Kafka Consumer Lag
```bash
# Check consumer groups
kubectl exec -it kafka-broker -- bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe --group broadcast-service-group
```

### Debug Commands
```bash
# Check application health
curl http://localhost:8081/api/actuator/health

# Check metrics
curl http://localhost:8081/api/actuator/prometheus

# Check environment variables
kubectl exec -it <pod-name> -- env

# Check thread dump
kubectl exec -it <pod-name> -- jstack <pid>
```

## Development

### Local Development
```bash
# Start with dev profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Run tests
mvn test

# Build and test
mvn clean package
```

### Testing
- **Unit Tests**: JUnit 5 with Mockito
- **Integration Tests**: Spring Boot Test with Testcontainers
- **Performance Tests**: JMeter or Gatling for load testing

### Code Quality
- **Code Style**: Google Java Style Guide
- **Static Analysis**: SpotBugs, PMD, Checkstyle
- **Code Coverage**: JaCoCo for coverage reporting

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For support and questions:
- Create an issue in the repository
- Contact the development team
- Check the documentation

## Changelog

### Version 1.0.0
- Initial release
- Core broadcast functionality
- SSE real-time delivery
- Kafka integration
- Caffeine caching
- Kubernetes deployment
- Comprehensive monitoring