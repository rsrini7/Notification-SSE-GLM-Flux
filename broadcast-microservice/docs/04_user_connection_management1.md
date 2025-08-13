# User Connection Management


The Broadcast Microservice manages user connections for Server-Sent Events (SSE) to ensure real-time communication and efficient resource utilization. This document outlines the key components and flows involved in user connection management, including connection creation, registration, heartbeat updates, and cleanup.

## Key Components

### 1. SseConnectionManager

<mcsymbol name="SseConnectionManager" filename="SseConnectionManager.java" path="c:\Users\Srini\ws\Notification-SSE-GLM-Flux\broadcast-microservice\src\main\java\com\example\broadcast\user\service\SseConnectionManager.java" startline="37" type="class"></mcsymbol> is the core component responsible for managing the low-level technical aspects of SSE connections. It maintains in-memory state for connections on the current pod and handles the lifecycle of SSE streams.

**Responsibilities:**
- Creating and storing in-memory sinks for each client connection.
- Tracking active user-to-connection mappings.
- Persisting connection state to the database or Redis.
- Sending periodic heartbeats to keep connections alive.
- Cleaning up stale or disconnected connections from memory and the distributed store.

### 2. DistributedConnectionManager

<mcsymbol name="DistributedConnectionManager" filename="DistributedConnectionManager.java" path="c:\Users\Srini\ws\Notification-SSE-GLM-Flux\broadcast-microservice\src\main\java\com\example\broadcast\user\service\DistributedConnectionManager.java" startline="11" type="class"></mcsymbol> is an interface that abstracts the underlying connection storage mechanism. It provides methods for registering, removing, updating heartbeats, and querying connection information in a distributed environment.

**Implementations:**
- <mcsymbol name="RedisConnectionManager" filename="RedisConnectionManager.java" path="c:\Users\Srini\ws\Notification-SSE-GLM-Flux\broadcast-microservice\src\main\java\com\example\broadcast\user\service\RedisConnectionManager.java" startline="14" type="class"></mcsymbol>: Manages connections using Redis as the distributed store. 

### 3. SseController

<mcsymbol name="SseController" filename="SseController.java" path="c:\Users\Srini\ws\Notification-SSE-GLM-Flux\broadcast-microservice\src\main\java\com\example\broadcast\user\controller\SseController.java" startline="20" type="class"></mcsymbol> handles incoming HTTP requests for SSE connections, disconnections, and connection statistics.

### 4. SseService

<mcsymbol name="SseService" filename="SseService.java" path="c:\Users\Srini\ws\Notification-SSE-GLM-Flux\broadcast-microservice\src\main\java\com\example\broadcast\user\service\SseService.java" startline="20" type="class"></mcsymbol> acts as an orchestrator, coordinating between <mcsymbol name="SseConnectionManager" filename="SseConnectionManager.java" path="c:\Users\Srini\ws\Notification-SSE-GLM-Flux\broadcast-microservice\src\main\java\com\example\broadcast\user\service\SseConnectionManager.java" startline="37" type="class"></mcsymbol> and other services for message delivery and connection management.

### 5. UserConnectionCleanupService

This is a scheduled service (documented in <mcfile name="07_scheduler_flow2.md" path="docs/07_scheduler_flow2.md"></mcfile>) responsible for purging old, inactive user connections from the database.

## Connection Management Flow

### 1. Connection Establishment

When a client connects to the SSE endpoint (`/api/user/sse/connect`):

```mermaid
sequenceDiagram
    participant Client
    participant SseController
    participant SseService
    participant SseConnectionManager
    participant DistributedConnectionManager
    participant CacheService

    Client->>SseController: GET /api/user/sse/connect (userId, connectionId)
    SseController->>SseService: registerConnection(userId, connectionId)
    SseService->>SseConnectionManager: registerConnection(userId, connectionId)
    SseConnectionManager->>DistributedConnectionManager: registerConnection(userId, connectionId, podId)
    DistributedConnectionManager-->>SseConnectionManager: Connection registered in DB/Redis
    SseConnectionManager->>CacheService: registerUserConnection(userId, connectionId, podId)
    CacheService-->>SseConnectionManager: User connection registered in cache
    SseConnectionManager-->>SseService: Connection registered
    SseService->>SseConnectionManager: createEventStream(userId, connectionId)
    SseConnectionManager-->>SseService: Flux<ServerSentEvent<String>>
    SseService->>SseService: sendPendingMessages(userId)
    SseService->>SseConnectionManager: sendEvent(userId, CONNECTED_EVENT)
    SseConnectionManager-->>SseService: Event sent
    SseService-->>SseController: Flux<ServerSentEvent<String>>
    SseController-->>Client: SSE Stream (200 OK)
```

### 2. Heartbeat Updates

To keep connections alive and track active connections, <mcsymbol name="SseConnectionManager" filename="SseConnectionManager.java" path="c:\Users\Srini\ws\Notification-SSE-GLM-Flux\broadcast-microservice\src\main\java\com\example\broadcast\user\service\SseConnectionManager.java" startline="37" type="class"></mcsymbol> sends periodic heartbeats and updates the `lastHeartbeat` timestamp for active connections.

```mermaid
sequenceDiagram
    participant SseConnectionManager
    participant DistributedConnectionManager
    participant Client

    loop Every heartbeatInterval
        SseConnectionManager->>SseConnectionManager: startServerHeartbeat()
        SseConnectionManager->>DistributedConnectionManager: updateHeartbeats(podId, connectionIdsOnThisPod)
        DistributedConnectionManager-->>SseConnectionManager: Heartbeats updated in DB/Redis
        SseConnectionManager->>Client: HEARTBEAT ServerSentEvent
    end
```

### 3. Connection Termination and Stale Connection Cleanup

When a client disconnects or a connection becomes stale, the system initiates cleanup processes.

```mermaid
sequenceDiagram
    participant Client
    participant SseController
    participant SseService
    participant SseConnectionManager
    participant DistributedConnectionManager
    participant CacheService
    participant UserConnectionCleanupService

    alt Client Disconnects
        Client->>SseController: POST /api/user/sse/disconnect (userId, connectionId)
        SseController->>SseService: removeEventStream(userId, connectionId)
        SseService->>SseConnectionManager: removeEventStream(userId, connectionId)
        SseConnectionManager->>SseConnectionManager: Remove from in-memory maps
        SseConnectionManager->>DistributedConnectionManager: removeConnection(userId, connectionId, podId)
        DistributedConnectionManager-->>SseConnectionManager: Connection marked INACTIVE in DB/Redis
        SseConnectionManager->>CacheService: unregisterUserConnection(userId, connectionId)
        CacheService-->>SseConnectionManager: User connection unregistered from cache
        SseConnectionManager-->>SseService: Connection removed
        SseService-->>SseController: Success
        SseController-->>Client: 200 OK
    else Stale Connection Cleanup (Scheduled)
        UserConnectionCleanupService->>DistributedConnectionManager: getStaleConnectionIds(thresholdTimestamp)
        DistributedConnectionManager-->>UserConnectionCleanupService: List of stale connection IDs
        UserConnectionCleanupService->>DistributedConnectionManager: markConnectionsInactive(staleConnectionIds)
        DistributedConnectionManager-->>UserConnectionCleanupService: Connections marked INACTIVE in DB/Redis
        UserConnectionCleanupService->>SseConnectionManager: removeEventStream (for local pod's stale connections)
        SseConnectionManager->>SseConnectionManager: Remove from in-memory maps
        SseConnectionManager->>CacheService: unregisterUserConnection (for local pod's stale connections)
    end
```