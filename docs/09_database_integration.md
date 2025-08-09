# 09. Database Integration

## Motivation
The Broadcast Microservice uses a relational database (PostgreSQL in production, H2 for development/testing) for persistent storage of broadcast messages, user sessions, and related metadata. This ensures data durability and consistency.

## Core Components
1. **JDBC Template**: Spring's `JdbcTemplate` is used for interacting with the database, providing a simplified way to perform JDBC operations.

2. **BroadcastRepository** (<mcfile name="BroadcastRepository.java" path="broadcast-microservice/src/main/java/com/example/broadcast/shared/repository/BroadcastRepository.java"></mcfile>): Manages CRUD operations for `BroadcastMessage` entities, including saving, updating, and querying broadcast details and their statistics.

## Key Features
- **Relational Data Model**: Stores structured data for broadcasts and user sessions.
- **JDBC-based Persistence**: Direct and efficient database interaction using `JdbcTemplate`.
- **Transactional Operations**: Ensures data integrity for complex operations.
- **Session Management**: Tracks user connection status and provides mechanisms for session cleanup.

## Data Models
- **BroadcastMessage**: Represents a broadcast message with details like sender, content, target, schedule, and status.
- **UserSession**: Stores information about active and inactive user sessions, including `userId`, `sessionId`, `podId`, `connectionStatus`, and timestamps.

## Example: Saving a Broadcast Message
```java:broadcast-microservice/src/main/java/com/example/broadcast/shared/repository/BroadcastRepository.java
// ... existing code ...

    public BroadcastMessage save(BroadcastMessage broadcast) {
        String sql = """
            INSERT INTO broadcast_messages
            (sender_id, sender_name, content, target_type, target_ids, priority, category, scheduled_at, expires_at, status, is_fire_and_forget)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, broadcast.getSenderId());
            ps.setString(2, broadcast.getSenderName());
            ps.setString(3, broadcast.getContent());
            ps.setString(4, broadcast.getTargetType());
            ps.setString(5, JsonUtils.toJsonArray(broadcast.getTargetIds()));
            ps.setString(6, broadcast.getPriority());
            ps.setString(7, broadcast.getCategory());

            if (broadcast.getScheduledAt() != null) {
                ps.setObject(8, broadcast.getScheduledAt().toOffsetDateTime());
            } else {
                ps.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE);
            }
        
            if (broadcast.getExpiresAt() != null) {
                ps.setObject(9, broadcast.getExpiresAt().toOffsetDateTime());
            } else {
                ps.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE);
            }

            ps.setString(10, broadcast.getStatus() != null ?
                    broadcast.getStatus() : BroadcastStatus.ACTIVE.name());
            
            ps.setBoolean(11, broadcast.isFireAndForget()); // Set new parameter

            return ps;
        }, keyHolder);
        
        if (keyHolder.getKeyList() != null && !keyHolder.getKeyList().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeyList().get(0);
            Number id = (Number) keys.get("id"); // Check for Postgres's lowercase 'id' first
            if (id == null) {
                id = (Number) keys.get("ID"); // Fallback to H2's uppercase 'ID'
            }
            if (id != null) {
                broadcast.setId(id.longValue());
            } else {
                throw new RuntimeException("Generated key 'id' not found in the returned keys.");
            }
        } else if (keyHolder.getKey() != null) {
            broadcast.setId(keyHolder.getKey().longValue());
        } else {
            throw new RuntimeException("Failed to retrieve generated key for broadcast.");
        }
        
        return broadcast;
    }

// ... existing code ...
```