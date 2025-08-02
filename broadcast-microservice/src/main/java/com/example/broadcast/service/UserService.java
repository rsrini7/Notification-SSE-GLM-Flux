package com.example.broadcast.service;

import org.springframework.stereotype.Service;
import java.util.List;

/**
 * A simulated service to represent an external dependency for fetching user data.
 * In a real application, this would make network calls to a user management service.
 * For this project, it is a placeholder and data should be managed in data.sql or external user service.
 */
@Service
public class UserService {

    /**
     * Simulates fetching all user IDs from an external service.
     * @return A list of user IDs.
     */
    public List<String> getAllUserIds() {
        // In a real application, this would make a network call.
        // This could also be a database lookup depending on the architecture.
        // A proper implementation would have a WebClient call here.

        //useSseConnection.ts:80  POST https://localhost/api/sse/heartbeat?userId=user-117&sessionId=session-1754111099026-trcv6by5p net::ERR_INSUFFICIENT_RESOURCES
        return java.util.stream.IntStream.rangeClosed(1, 50)
                .mapToObj(i -> String.format("user-%03d", i))
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Simulates fetching user IDs for a specific role from an external service.
     * @param role The role to fetch users for.
     * @return A list of user IDs belonging to the specified role.
     */
    public List<String> getUserIdsByRole(String role) {
        // Mock response for users in a role. In a real app, this would be a network call.
        // This could also be a database lookup depending on the architecture.
        if ("admin".equalsIgnoreCase(role)) {
            return List.of("user-002", "user-003");
        }
        return List.of("user-001", "user-005");
    }
}