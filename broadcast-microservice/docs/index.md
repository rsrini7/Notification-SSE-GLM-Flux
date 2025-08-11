# Broadcast Messaging System - Microservice

Welcome to the documentation for the Broadcast Messaging System Microservice, a high-performance Java microservice built with Spring Boot and Netty for real-time message delivery.

## Project Overview

The Broadcast Messaging System is designed to handle high-scale operations with support for 400,000+ registered users and 30,000+ concurrent SSE connections. It provides real-time message delivery with sub-second latency for online users, persistent storage for message tracking, and a scalable architecture ready for Kubernetes deployment.

## Project Architecture

```mermaid
graph TD
    subgraph "User Interface"
        A[Web Browser] -- "HTTP/HTTPS" --> B(Nginx Reverse Proxy)
    end

    subgraph "Frontend"
        B -- "Proxies to" --> C(broadcast-frontend)
        C -- "Real-time SSE" --> D(broadcast-microservice)
        C -- "REST API" --> D
    end

    subgraph "Backend Core"
        D(broadcast-microservice) -- "Publishes Events" --> E(Kafka Broker)
        D -- "Reads/Writes" --> F(Postgres/H2 Database)
        D -- "Caches Data" --> G(Redis Cache)
    end

    subgraph "Event Processing"
        E -- "Consumes Events" --> H(Kafka Consumer)
        H -- "Processes" --> D
        E -- "DLT" --> I(Dead Letter Topic)
    end

    subgraph "Message Delivery"
        D -- "SSE Connections" --> J(SseConnectionManager)
        J -- "Delivers to" --> A
    end

    subgraph "Monitoring"
        D -- "Metrics" --> K(Prometheus)
        D -- "Health Checks" --> L(Kubernetes Probes)
    end

    classDef default fill:#f9f9f9,stroke:#333,stroke-width:1px;
    classDef database fill:#f5d7f9,stroke:#333,stroke-width:1px;
    classDef cache fill:#fffacd,stroke:#333,stroke-width:1px;
    classDef eventstream fill:#d7f9f9,stroke:#333,stroke-width:1px;
    classDef monitoring fill:#d7f9d7,stroke:#333,stroke-width:1px;

    class F,I database
    class G cache
    class E,H eventstream
    class K,L monitoring
```

## Chapters

0. [React Frontend](00_react_frontend.md)
1. [Message Broadcasting System](01_message_broadcasting_system.md)
2. [Server-Sent Events (SSE)](02_server_sent_events.md)
3. [Kafka Event Streaming](03_kafka_event_streaming.md)
4. [User Connection Management](04_user_connection_management1.md)
5. [User Connection Management](04_user_connection_management2.md)
6. [Dead Letter Topic (DLT) Management](05_dlt_management.md)
7. [Database Schema Design](06_database_schema_design.md)
8. [Redis Caching](06_redis_caching.md)
9. [Scheduler Flow](07_scheduler_flow1.md)
10. [Scheduler Flow](07_scheduler_flow2.md)
