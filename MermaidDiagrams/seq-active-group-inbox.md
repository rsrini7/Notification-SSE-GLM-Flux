sequenceDiagram
    participant UserUI as User UI
    participant SseSvc as SseService
    participant UserMsgSvc as UserMessageService
    participant RedisCache as Redis Cache Service
    participant DB as Database

    UserUI->>+SseSvc: 1- Establishes SSE Connection
    Note over SseSvc: Handles connection registration in Redis...
    SseSvc->>SseSvc: 2- Delivers pending messages from Redis cache
    SseSvc-->>-UserUI: Pushes any pending messages via SSE
    
    UserUI->>+UserMsgSvc: 3- GET /api/user/messages (to populate inbox)
    Note over UserMsgSvc: 4- Begins complex fetch logic... <br/>(as detailed in graph-active-group-inbox.mmd)
    UserMsgSvc->>+RedisCache: 5- Checks various caches (user-msg, active-group, broadcast-content)
    alt Some caches are missed
        RedisCache-->>UserMsgSvc: 
        UserMsgSvc->>+DB: 6- Queries database for required information
        DB-->>-UserMsgSvc: 
        UserMsgSvc->>RedisCache: 7- Populates the missing caches
    end
    RedisCache-->>-UserMsgSvc: 
    UserMsgSvc-->>-UserUI: 8- Returns the complete and up-to-date inbox