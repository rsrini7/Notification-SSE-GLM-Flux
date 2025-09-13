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
-   **End-to-End Observability**: The entire message lifecycle is instrumented with OpenTelemetry, providing distributed traces, metrics, and structured logs.
-   **Scalable Deployment**: Ready for Kubernetes with Horizontal Pod Autoscaling (HPA).

### System Design Diagram
```mermaid
graph TD
    subgraph "User Action"
        AdminUI[Admin UI]
    end

    subgraph "1. Creation & Triage (Admin Service)"
        AdminAPI["API Layer"]
        LifecycleSvc["BroadcastLifecycleService"]
        CreationTriage{"Broadcast Type?"}
    end

    subgraph "2. Async Pre-computation (Admin Service)"
        PrecomputationScheduler["Precomputation Scheduler<br/>(Every 1 min)"]
        AsyncPrecomputation["Async TargetingService<br/>(Fan-out on Write)"]
    end

    subgraph "3. Activation (Admin Service)"
        ActivationScheduler["Activation Scheduler<br/>(Every 1 min)"]
    end

    subgraph "4. Publishing (Admin Service)"
        OutboxPoller["Outbox Polling Service<br/>(Every 2s)"]
    end

    subgraph "5. Delivery (User Service & Infra)"
        OrchestratorConsumer["Orchestrator Consumer (Kafka)"]
        WorkerCqListener["Worker CqListener (Geode)"]
        SseService["SSE Service"]
        Kafka["Kafka (Orchestration Topic)"]
        Geode["Geode (Cache & Eventing)"]
        Postgres["PostgreSQL DB"]
        User[User Browser]
    end
    
    %% --- FLOW START ---
    AdminUI --> AdminAPI --> LifecycleSvc --> CreationTriage

    %% --- PATH A: IMMEDIATE BROADCASTS ---
    CreationTriage -- "Immediate" --> ImmediateType{"Fan-out on Read or Write?"}
    ImmediateType -- "Read (ALL/ROLE/SELECTED)" --> SaveActiveAndOutbox["DB: status=ACTIVE<br/>INSERT into outbox_events"]
    ImmediateType -- "Write (PRODUCT)" --> SavePreparing["DB: status=PREPARING"]
    
    SavePreparing -- "Triggers" --> AsyncPrecomputation

    %% --- PATH B: SCHEDULED BROADCASTS ---
    CreationTriage -- "Scheduled" --> SaveScheduled["DB: status=SCHEDULED"]
    SaveScheduled --> ScheduledType{"Fan-out on Read or Write?"}
    
    ScheduledType -- "Write (PRODUCT)" --> PrecomputationScheduler
    PrecomputationScheduler -- "Polls for due broadcasts" --> Postgres
    PrecomputationScheduler --> SavePreparing
    
    ScheduledType -- "Read (ALL/ROLE/SELECTED)" --> ActivationScheduler

    %% --- PRECOMPUTATION & ACTIVATION FLOW ---
    AsyncPrecomputation -- "Saves targets & sets status=READY" --> Postgres
    Postgres -- "status=READY" --> ActivationScheduler
    
    ActivationScheduler -- "Polls for READY or<br/>due SCHEDULED broadcasts" --> Postgres
    ActivationScheduler --> SaveActiveAndOutbox
    
    %% --- CONVERGENCE & PUBLISHING ---
    SaveActiveAndOutbox --> Postgres
    Postgres -- "Event waits in 'outbox_events'" --> OutboxPoller
    OutboxPoller -- "Polls & Locks Event" --> Postgres
    OutboxPoller -- "Publishes ONE Event" --> Kafka

    %% --- DELIVERY ---
    Kafka --> OrchestratorConsumer
    OrchestratorConsumer -- "Determines Audience & Pod, <br/> Puts event into Geode Region" --> Geode
    Geode -- "CQ invokes Listener" --> WorkerCqListener
    WorkerCqListener --> SseService
    SseService --> User
```

## License

This project is part of the Broadcast Messaging System.
