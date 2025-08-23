# Broadcast Messaging System

This repository contains a full-stack broadcast messaging system, consisting of a modern React frontend and a high-performance Java microservice.

## Overview

The project is divided into two main components:

-   **[Frontend](./broadcast-frontend/README.md)**: A React application built with Vite, TypeScript, and Tailwind CSS. It provides a user interface for sending and receiving broadcast messages.
-   **[Microservice](./broadcast-microservice/README.md)**: A Java-based microservice built with Spring Boot and Netty. It handles the backend logic, including real-time message delivery via Server-Sent Events (SSE), integration with Kafka, and database management.

For detailed information about each component, please refer to the `README.md` files in their respective directories.

## System Architecture

The overall system architecture is designed for scalability and real-time performance. It includes:

-   **Real-time SSE Delivery**: For instant message delivery to online users.
-   **Event-Driven Architecture**: Using Kafka for asynchronous communication.
-   **High-Performance Caching**: With Geode / Gemfire for low-latency operations.
-   **Scalable Deployment**: Ready for Kubernetes with Horizontal Pod Autoscaling (HPA).

### System Design Diagram
```mermaid
graph TD
    subgraph "User & Admin UI"
        AdminUser[Admin User]
        ReactUI[React Frontend App]
    end
    subgraph "Admin Service (broadcast-admin-service)"
        AdminController[Admin API Controller]
        LifecycleService[Broadcast Lifecycle Service]
        DltListener[DLT Consumer Service]
        
        subgraph "Schedulers (run on one pod)"
            OutboxPoller["Outbox Polling Service<br/>(every 2s)"]
            SchedulingService["Scheduled Broadcast Service<br/>(every 1m)"]
            ExpirationService["Broadcast Expiration Service<br/>(every 1m)"]
        end
    end
    subgraph "User Service (broadcast-user-service)"
        UserController[User API & SSE Controller]
        KafkaConsumer[Kafka Message Consumer]
        SseService[SSE Service & Connection Manager]
    end
    subgraph "Shared Infrastructure"
        Kafka["Kafka Broker broadcast-user-service-selected broadcast-user-service-group ...-dlt"]
        PostgresDB["PostgreSQL DB broadcast_messages user_broadcast_messages outbox_events dlt_messages"]
        GeodeCache["Geode Cache online-users pending-events"]
    end
    
    OnlineCheck{8 Is User Online?}
    KafkaDLT[DLT Topic]
    EndOfflinePath(( ))
    
    AdminUser -->|"1- Creates Broadcast"| ReactUI
    ReactUI -->|"2- POST /broadcasts"| AdminController
    AdminController --> LifecycleService
    LifecycleService -->|"3- DB Transaction"| PostgresDB
    OutboxPoller -->|"4- Polls & Locks Events"| PostgresDB
    OutboxPoller -->|"5- Publishes CREATED Event"| Kafka
    Kafka -->|"6- Delivers Event"| KafkaConsumer
    KafkaConsumer -->|"7- Checks Geode for User Status"| OnlineCheck
    OnlineCheck -->|"Yes"| SseService
    SseService -->|"9a- Pushes Message via SSE"| ReactUI
    SseService -->|"Updates Status to DELIVERED"| PostgresDB
    
    OnlineCheck -->|"No"| GeodeCache
    GeodeCache -->|"9b- Caches Pending Event in pending-evt:userId"| EndOfflinePath
    
    ReactUI -->|"A- DELETE /broadcasts/{id} (Cancel)"| AdminController
    AdminController -->|"(Updates DB & creates Outbox event)"| LifecycleService
    LifecycleService -->|"Publishes CANCELLED Event"| OutboxPoller
    KafkaConsumer -.->|"(Removes from Cache & Notifies UI)"| SseService
    
    ReactUI -->|"B- POST /messages/read"| UserController
    UserController -->|"(Updates DB & creates Outbox event)"| PostgresDB
    OutboxPoller -->|"Publishes READ Event"| Kafka
    KafkaConsumer -->|"(Pushes READ_RECEIPT via SSE)"| SseService
    
    KafkaConsumer -->|"C- Message Processing Fails"| KafkaDLT
    KafkaDLT --> DltListener
    DltListener -->|"(Consumes from DLT & stores in DB)"| PostgresDB
    
    SchedulingService -->|"D- Activates due broadcasts"| PostgresDB
    ExpirationService -->|"E- Expires old broadcasts"| PostgresDB
    
    classDef userAdmin fill:#d4edff,stroke:#333
    classDef adminService fill:#fff2cc,stroke:#333
    classDef userService fill:#e2f0d9,stroke:#333
    classDef infrastructure fill:#cce5ff,stroke:#333
    classDef dlt fill:#f8d7da,stroke:#333
    classDef cache fill:#f5c6cb,stroke:#333
    
    class AdminUser,ReactUI userAdmin
    class AdminController,LifecycleService,OutboxPoller,SchedulingService,ExpirationService,DltListener adminService
    class UserController,KafkaConsumer,SseService userService
    class Kafka,PostgresDB infrastructure
    class KafkaDLT dlt
    class GeodeCache cache
```

## License

This project is part of the Broadcast Messaging System.
