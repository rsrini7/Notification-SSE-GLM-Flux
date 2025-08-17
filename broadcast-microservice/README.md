# Broadcast Messaging System - Microservice

A high-performance Java microservice for the Broadcast Messaging System, built with Spring Boot and Netty.

## Features

- **Real-time SSE Delivery**: Sub-second latency for online users
- **Persistent Storage**: h2 Database with admin and user-side tracking
- **Event Streaming**: Kafka-based fan-out with at-least-once semantics
- **High-Performance Caching**: Redis for low-latency operations
- **Scalable Architecture**: Kubernetes-ready with HPA and PDB

## Tech Stack

- **Java 17+**: Backend language.
- **Spring Boot**: Java backend framework.
- **Netty**: High-performance network library for Java.
- **Postgres**: Relational database for message storage.
- **Redis**: In-memory data structure store for caching and message queuing.
- **Docker**: Containerization for easy deployment.
- **Nginx**: Reverse proxy and load balancer.

### Technical Capabilities
- **Reactive Programming**: Spring WebFlux with non-blocking I/O
- **Database Optimization**: Batch operations, proper indexing, connection pooling
- **Message Delivery**: Guaranteed delivery with retry mechanisms
- **Monitoring & Observability**: Comprehensive metrics, logging, and tracing
- **High Availability**: Multi-replica deployment with failover

### Data Flow
1. **Admin creates broadcast** → Stored in h2 DB → Kafka event published
2. **Kafka consumer** processes event → Updates cache → Delivers via SSE
3. **User receives message** → Marks as read → Status updated in DB
4. **Offline users** → Messages cached → Delivered on reconnect

## Prerequisites

- Java 17+
- Maven 3.6+
- Docker & Kubernetes (for deployment)
- Kafka 3.7.1+ (Confluence 7.7.1)
- Postgres 15+
- Redis 7+

## Database Schema

### Core Tables
- **broadcast_messages**: Admin-side broadcast records
- **user_broadcast_messages**: User-specific delivery tracking
- **user_preferences**: Notification preferences
- **broadcast_statistics**: Performance metrics
- **dlt_messages**: Dead Letter Topic for failed messages
- **outbox_events**: Outbox table for event sourcing
- **shedlock**:  Shedlock table to prevent duplicate execution of scheduled tasks across multiple application instances

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

### Actuator
- **Endpoint**: `/actuator/redis-cache-stats`
- **Description**: Provides cache statistics for Redis.
- **Usage**: Access this endpoint to monitor cache performance.
- **Note**: This endpoint is only available when the Redis profile is active.

## H2 Console

- Webflux and h2 console not supporting default by enabling h2 console in application.yml. 
- We need to disable h2 console in application.yml and enable it in H2ConsoleConfig.java.

-   `http://localhost:8083` - H2 Console
-   `jdbc:h2:~/broadcast-system/broadcastdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;MODE=PostgreSQL` - JDBC URL
-   `sa` - Username
-   `` - Password

## Backend Project Structure

```
broadcast-microservice/
├── KUBERNETES_GUIDE.md               # Kubernetes deployment guide
├── README.md                         # Project documentation
├── gen-p12.bat                       # Certificate generation script
├── pom.xml                           # Main Maven project file
├── broadcast-admin-service/          # Admin service module
│   ├── Dockerfile                    # Docker configuration
│   ├── pom.xml                       # Admin service Maven file
│   ├── src/                          # Source code
├── broadcast-shared/                 # Shared module
│   ├── pom.xml                       # Shared module Maven file
│   ├── src/                          # Shared source code
├── broadcast-user-service/           # User service module
│   ├── pom.xml                       # User service Maven file
│   ├── src/                          # Source code
│   └── ...                           # Similar structure to admin service
└── docs/                             # Documentation
```

## Development

### spring boot profile based start

```bash
# Generate SSL keystore for the microservice
gen-p12.bat

# Start with Postgres profile
mvn spring-boot:run "-Dspring-boot.run.profiles=pg-dev"

# Build and run with Postgres profile
mvn clean package && java "-Dspring.profiles.active=pg-dev" -jar target/broadcast-admin-service-1.0.0.jar
mvn clean package && java "-Dspring.profiles.active=pg-dev" -jar target/broadcast-user-service-1.0.0.jar
```

## Deployment

### Kubernetes Deployment
```bash
# Apply all configurations
kubectl apply -f k8s/

# Verify deployment
kubectl get pods -n broadcast-system
kubectl get hpa -n broadcast-system
```

### Performance Testing

```bash
k6 run --insecure-skip-tls-verify sse-test.js
mvn gatling:test
```

## Troubleshooting

### Startup Tips

- **Redis Profile**: Use `mvn spring-boot:run -Dspring-boot.run.profiles=redis` to start with Redis profile.
- **PostgreSQL Profile**: Use `mvn spring-boot:run -Dspring-boot.run.profiles=dev-pg` to start with PostgreSQL profile.
- **Database Initialization**: For first-time setup, set `spring.sql.init.mode=always` in `application.yml`. After initial schema creation, change to `spring.sql.init.mode=never` and ensure `schema.sql` comment `SET MODE PostgreSQL;` for PostgreSQL then uncomment for h2.

## Project Architecture

### System Design Diagram Backend
```mermaid
graph TD
    subgraph "User Interaction & Frontend"
        AdminUser[Admin User]
        UI[React Frontend App]
        AdminUser -- Manages Broadcasts --> UI
    end

    subgraph "Entry & Caching"
        Nginx[Nginx Reverse Proxy]
        Redis[Redis Cache]
    end

    subgraph "Backend: Admin Service"
        AdminController[BroadcastAdminController]
        LifecycleService[BroadcastLifecycleService]
        DltService[DltService]
        OutboxPoller["OutboxPollingService (Scheduled)"]
    end

    subgraph "Backend: User Service"
        UserController[UserMessageController & SseController]
        KafkaConsumer[KafkaConsumerService]
        SseService[SseService]
    end

    subgraph "Data & Eventing Infrastructure"
        KafkaTopic[Kafka Topic broadcast-events-selected/group]
        KafkaDLT[Kafka DLT...-dlt]
        Postgres[PostgreSQL Database]
    end

    %% Styles
    style AdminUser fill:#d4edff,stroke:#333
    style UI fill:#d4edff,stroke:#333
    style Nginx fill:#e2f0d9,stroke:#333
    style AdminController fill:#fff2cc,stroke:#333
    style LifecycleService fill:#fff2cc,stroke:#333
    style DltService fill:#f8d7da,stroke:#333
    style OutboxPoller fill:#fff2cc,stroke:#333
    style UserController fill:#fff2cc,stroke:#333
    style KafkaConsumer fill:#fff2cc,stroke:#333
    style SseService fill:#fff2cc,stroke:#333
    style Postgres fill:#d6d8db,stroke:#333
    style Redis fill:#f5c6cb,stroke:#333
    style KafkaTopic fill:#cce5ff,stroke:#333
    style KafkaDLT fill:#f8d7da,stroke:#333


    %% Happy Path: Broadcast Creation and Delivery
    UI -- 1- POST /api/admin/broadcasts --> Nginx
    Nginx -- 2- Proxies Request --> AdminController
    AdminController -- 3- createBroadcast --> LifecycleService
    LifecycleService -- "4- DB Transaction" --> Postgres
    Postgres -- "Saves to broadcast_messages & outbox_events" --> LifecycleService
    OutboxPoller -- 5- Polls for new events --> Postgres
    OutboxPoller -- 6- Publishes Event --> KafkaTopic
    KafkaConsumer -- 7- Consumes Event --> KafkaTopic
    KafkaConsumer -- 8- Is user online? --> Redis
    KafkaConsumer -- 9a- If Online --> SseService
    SseService -- "10- Pushes SSE Event" --> UI
    SseService -- "Updates Status to DELIVERED" --> Postgres
    KafkaConsumer -- 9b- If Offline --> Redis
    Redis -- "Caches in pending-evt:<userId>" --> KafkaConsumer
    
    %% User Reconnection Path
    UI -- "User Connects" --> UserController
    UserController -- "Calls on connect" --> SseService
    SseService -- "Gets pending messages" --> Redis
    SseService -- "Pushes pending messages via SSE" --> UI

    %% DLT Path: Failure and Redrive
    KafkaConsumer -- "A- Processing Fails" --> KafkaConsumer
    KafkaConsumer -- "B- After Retries, sends to DLT" --> KafkaDLT
    DltService -- "C- Consumes from DLT" --> KafkaDLT
    DltService -- "D- Saves to dlt_messages table" --> Postgres
    UI -- "E- Admin clicks 'Redrive'" --> Nginx
    Nginx -- "F- POST /api/admin/dlt/redrive" --> DltService
    DltService -- "G- Resets message status" --> Postgres
    DltService -- "H- Re-publishes original event" --> KafkaTopic
```