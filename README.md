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
-   **High-Performance Caching**: With Redis for low-latency operations.
-   **Scalable Deployment**: Ready for Kubernetes with Horizontal Pod Autoscaling (HPA).

### System Design Diagram
```mermaid
graph TD
    subgraph "User Interface"
        A[Web Browser] -- "HTTP/HTTPS" --> B(Nginx Reverse Proxy)
    end

    subgraph "Frontend (React/Vite)"
        B -- "Proxies to" --> C(broadcast-frontend)
        C -- "Real-time SSE" --> D(broadcast-microservice)
        C -- "REST API" --> D
    end

    subgraph "Backend (Java/Spring Boot/Netty)"
        D(broadcast-microservice) -- "Publishes Events" --> E(Kafka Broker)
        D -- "Reads/Writes" --> F(Postgres Database)
        D -- "Caches Data" --> G(Redis Cache)
    end

    subgraph "Event Streaming"
        E -- "Consumes/Produces" --> D
        E -- "DLT" --> H(Dead Letter Topic)
    end

    subgraph "Data Storage"
        F -- "Stores Broadcasts, User Messages" --> D
    end

    subgraph "Caching"
        G -- "High-Performance Caching" --> D
    end

    subgraph "Monitoring & Observability"
        D -- "Metrics, Logs, Traces" --> I(Monitoring System)
    end

    subgraph "Deployment (Kubernetes)"
        J(Kubernetes Cluster) -- "Deploys" --> D
        J -- "Manages" --> F
        J -- "Manages" --> E
        J -- "Manages" --> G
    end

    A -.-> K(Admin User)
    K -- "Manages Broadcasts" --> C

    classDef default fill:#fff,stroke:#333,stroke-width:2px;
    classDef database fill:#f9f,stroke:#333,stroke-width:2px;
    classDef cache fill:#ff9,stroke:#333,stroke-width:2px;
    classDef eventstream fill:#9ff,stroke:#333,stroke-width:2px;
    classDef deployment fill:#9f9,stroke:#333,stroke-width:2px;

    class F database;
    class G cache;
    class E eventstream;
    class J deployment;

    click A "Web Browser"
    click B "nginx.conf"
    click C "broadcast-frontend/README.md"
    click D "broadcast-microservice/README.md"
    click E "Kafka Broker"
    click F "Postgres Database"
    click G "Redis Cache"
    click H "Dead Letter Topic"
    click I "Monitoring System"
    click J "Kubernetes Cluster"
    click K "Admin User"
```

## License

This project is part of the Broadcast Messaging System.
