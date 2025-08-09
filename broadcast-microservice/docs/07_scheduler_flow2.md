# Scheduler Management and Flows

This document outlines the various scheduled tasks within the Broadcast Microservice, their purpose, and how they are managed, especially in a distributed environment using ShedLock.

## Overview of Scheduled Tasks

The Broadcast Microservice utilizes Spring's `@Scheduled` annotation for recurring tasks. To ensure these tasks run correctly and avoid conflicts in a multi-instance deployment, ShedLock is employed for distributed locking.

## 1. Outbox Polling Service

-   **Purpose:** Polls the outbox table for unprocessed events and publishes them to Kafka. This ensures reliable message delivery even if the initial Kafka send fails.
-   **Frequency:** Every 2 seconds (`fixedDelay = 2000`).
-   **Implementation:** Uses `@Scheduled(fixedDelay = 2000)` and `@Transactional` to ensure atomicity of polling and publishing.

```mermaid
sequenceDiagram
    participant P as OutboxPollingService
    participant DB as Database
    participant K as Kafka

    P->>DB: Poll for unprocessed events
    DB-->>P: Return list of events
    alt Events found
        loop For each event
            P->>K: Send event to Kafka (synchronous)
            K-->>P: Acknowledge/Error
            alt Kafka send successful
                P->>DB: Mark event as processed/delete
            else Kafka send failed
                P->>P: Rollback transaction (event remains in outbox)
            end
        end
    else No events found
        P->>P: Do nothing
    end
```

## 2. SSE Connection Manager - Stale Session Cleanup

-   **Purpose:** Periodically identifies and cleans up stale Server-Sent Event (SSE) sessions across the cluster. This ensures that inactive connections are properly closed and resources are released.
-   **Frequency:** Every 60 seconds (`fixedRate = 60000`).
-   **Distributed Lock:** `cleanupStaleSseSessions` using ShedLock (`lockAtLeastFor = "PT55S"`, `lockAtMostFor = "PT59S"`).
-   **Implementation:** Uses `@Scheduled(fixedRate = 60000)` and `@SchedulerLock`.

```mermaid
sequenceDiagram
    participant SCM as SseConnectionManager
    participant DSM as DistributedSessionManager
    participant CS as CacheService
    participant DB as Database

    SCM->>DSM: Get stale session IDs (cluster-wide)
    DSM-->>SCM: Return stale session IDs
    alt Stale sessions found
        loop For each stale session
            SCM->>DSM: Get session details
            DSM-->>SCM: Return session details (including pod ID)
            alt Session on current pod
                SCM->>SCM: Remove in-memory event stream
            else Session on other pod
                SCM->>CS: Unregister user connection
            end
        end
        SCM->>DSM: Remove stale sessions from distributed store
    else No stale sessions
        SCM->>SCM: Do nothing
    end
```

## 3. User Session Cleanup Service

-   **Purpose:** Purges old, inactive user sessions from the database to enforce data retention policies. This is a heavy deletion task.
-   **Frequency:** Daily at 2:00 AM (`cron = "0 0 2 * * *"`).
-   **Distributed Lock:** `purgeOldInactiveSessions` using ShedLock (`lockAtMostFor = "PT15M"`).
-   **Implementation:** Uses `@Scheduled(cron = "0 0 2 * * *")`, `@Transactional`, and `@SchedulerLock`.

```mermaid
sequenceDiagram
    participant USCS as UserSessionCleanupService
    participant DB as Database

    USCS->>USCS: Calculate retention threshold (e.g., 3 days ago)
    USCS->>DB: Delete inactive sessions older than threshold
    DB-->>USCS: Return count of deleted records
    USCS->>USCS: Log cleanup result
```

## 4. Broadcast Expiration Service

-   **Purpose:** Periodically checks for active broadcasts that have passed their `expires_at` timestamp and marks them as expired.
-   **Frequency:** Every 60 seconds (`fixedRate = 60000`).
-   **Distributed Lock:** `processExpiredBroadcasts` using ShedLock (`lockAtLeastFor = "PT55S"`, `lockAtMostFor = "PT59S"`).
-   **Implementation:** Uses `@Scheduled(fixedRate = 60000)`, `@Transactional`, and `@SchedulerLock`.

```mermaid
sequenceDiagram
    participant BES as BroadcastExpirationService
    participant BR as BroadcastRepository
    participant BLS as BroadcastLifecycleService
    participant DB as Database

    BES->>BR: Find expired broadcasts
    BR->>DB: Query for broadcasts with expires_at <= now
    DB-->>BR: Return list of expired broadcasts
    BR-->>BES: Return list
    alt Expired broadcasts found
        loop For each expired broadcast
            BES->>BLS: Expire broadcast (by ID)
            BLS->>DB: Update broadcast status to EXPIRED
        end
    else No expired broadcasts
        BES->>BES: Do nothing
    end
```

## 5. Broadcast Scheduling Service

-   **Purpose:** Periodically processes broadcasts that were `SCHEDULED` for a future time and are now due. It transitions them to an `ACTIVE` state.
-   **Frequency:** Every 60 seconds (`fixedRate = 60000`).
-   **Distributed Lock:** `processScheduledBroadcasts` using ShedLock (`lockAtLeastFor = "PT55S"`, `lockAtMostFor = "PT59S"`).
-   **Implementation:** Uses `@Scheduled(fixedRate = 60000)`, `@Transactional(noRollbackFor = UserServiceUnavailableException.class)`, and `@SchedulerLock`.

```mermaid
sequenceDiagram
    participant BSS as BroadcastSchedulingService
    participant BR as BroadcastRepository
    participant BLS as BroadcastLifecycleService
    participant DB as Database

    BSS->>BR: Find and lock scheduled broadcasts due now
    BR->>DB: Query for broadcasts with status=SCHEDULED and scheduled_at <= now
    DB-->>BR: Return list of due broadcasts
    BR-->>BSS: Return list
    alt Due broadcasts found
        loop For each due broadcast
            BSS->>BLS: Process scheduled broadcast (by ID)
            BLS->>DB: Update broadcast status to ACTIVE
        end
    else No due broadcasts
        BSS->>BSS: Do nothing
    end
```

This comprehensive overview covers the various scheduled operations critical to the Broadcast Microservice's functionality, from message delivery and session management to broadcast lifecycle handling, all orchestrated with robust distributed locking.